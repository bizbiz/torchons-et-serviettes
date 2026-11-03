"""Usage-threshold proposals for the modules no signal ever proposes.

Four modules have no natural event to hang a proposal on: `motivation`,
`bonus_malus`, `objectives` and `statistics`. They are proposed here, once a
foyer's usage says they would now be useful. The other modules keep their
signal call sites (pairing, presence, prompts) and are not touched.

A daily job (03:45 UTC, after the retard snapshot of 03:15 which one of the
triggers reads) evaluates every live foyer. All proposals still go through
`propose_module`, so the budget of `module_discovery` applies unchanged: foyer
age, the 5-day floor between two proposals, the refusal rules.

The thresholds below are PROVISIONAL: they were set before any real usage data
existed. Revisit at launch.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone
from typing import Awaitable, Callable

import structlog
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import async_session
from app.models.completion import TaskCompletion
from app.models.enums import PromptType
from app.models.foyer import Foyer
from app.models.foyer_retard_snapshot import FoyerRetardSnapshot
from app.models.module_state import ModuleState
from app.models.task import Task
from app.models.user_prompt import UserPrompt
from app.services.module_discovery import (
    REFUSAL_COOLDOWN_DAYS,
    can_propose,
    propose_module,
)
from app.services.modules import get_registry

logger = structlog.get_logger()

# --- Thresholds (provisional, revisit at launch) ---

#: motivation: the foyer has logged this many live completions.
MOTIVATION_MIN_COMPLETIONS = 20
#: statistics: the foyer's oldest live completion is at least this old.
STATISTICS_MIN_HISTORY_DAYS = 30
#: objectives: each of the last OBJECTIVES_SNAPSHOT_WINDOW daily retard
#: snapshots counts at least OBJECTIVES_MIN_OVERDUE_TASKS overdue tasks.
OBJECTIVES_SNAPSHOT_WINDOW = 3
OBJECTIVES_MIN_OVERDUE_TASKS = 3
#: bonus_malus: motivation has been enabled for at least this long.
BONUS_MALUS_MIN_DAYS_AFTER_MOTIVATION = 14

Condition = Callable[[AsyncSession, uuid.UUID], Awaitable[bool]]


async def _motivation_reached(session: AsyncSession, foyer_id: uuid.UUID) -> bool:
    count = await session.scalar(
        select(func.count(TaskCompletion.id))
        .join(Task, Task.id == TaskCompletion.task_id)
        .where(Task.foyer_id == foyer_id, TaskCompletion.deleted_at.is_(None))
    )
    return (count or 0) >= MOTIVATION_MIN_COMPLETIONS


async def _statistics_reached(session: AsyncSession, foyer_id: uuid.UUID) -> bool:
    oldest = await session.scalar(
        select(func.min(TaskCompletion.completed_at))
        .join(Task, Task.id == TaskCompletion.task_id)
        .where(Task.foyer_id == foyer_id, TaskCompletion.deleted_at.is_(None))
    )
    if oldest is None:
        return False
    age = datetime.now(timezone.utc) - oldest
    return age >= timedelta(days=STATISTICS_MIN_HISTORY_DAYS)


async def _objectives_reached(session: AsyncSession, foyer_id: uuid.UUID) -> bool:
    counts = (
        await session.execute(
            select(FoyerRetardSnapshot.task_count)
            .where(FoyerRetardSnapshot.foyer_id == foyer_id)
            .order_by(FoyerRetardSnapshot.date.desc())
            .limit(OBJECTIVES_SNAPSHOT_WINDOW)
        )
    ).scalars().all()
    # Fewer snapshots than the window is "not enough history", never a pass.
    return len(counts) == OBJECTIVES_SNAPSHOT_WINDOW and all(
        c >= OBJECTIVES_MIN_OVERDUE_TASKS for c in counts
    )


async def _bonus_malus_reached(session: AsyncSession, foyer_id: uuid.UUID) -> bool:
    enabled_at = await session.scalar(
        select(ModuleState.enabled_at).where(
            ModuleState.foyer_id == foyer_id,
            ModuleState.user_id.is_(None),
            ModuleState.module_key == "motivation",
            ModuleState.enabled.is_(True),
        )
    )
    if enabled_at is None:
        return False
    age = datetime.now(timezone.utc) - enabled_at
    return age >= timedelta(days=BONUS_MALUS_MIN_DAYS_AFTER_MOTIVATION)


async def _proposed_recently(
    session: AsyncSession, foyer_id: uuid.UUID, module_key: str
) -> bool:
    """Whether this module was already proposed here within the refusal cooldown.

    A usage condition stays true once met, and a proposal that nobody answers
    just expires. Without this check the first trigger in registry order would
    win the foyer's slot again every 5 days and the three after it would never
    get their turn. The same 30-day silence as a refusal, so a module is not
    pressed more often than one that was explicitly declined.
    """
    last = await session.scalar(
        select(func.max(UserPrompt.created_at)).where(
            UserPrompt.foyer_id == foyer_id,
            UserPrompt.type == PromptType.DISCOVER_MODULE,
            UserPrompt.context["module_key"].astext == module_key,
        )
    )
    if last is None:
        return False
    return datetime.now(timezone.utc) - last < timedelta(days=REFUSAL_COOLDOWN_DAYS)


#: module key -> (reason stored in the prompt, condition). Evaluated in registry
#: `order`, not in this dict's order, so the registry stays the one place that
#: ranks the modules.
USAGE_TRIGGERS: dict[str, tuple[str, Condition]] = {
    "motivation": ("completions_reached", _motivation_reached),
    "bonus_malus": ("after_motivation", _bonus_malus_reached),
    "objectives": ("backlog_threshold_reached", _objectives_reached),
    "statistics": ("history_volume_reached", _statistics_reached),
}


async def evaluate_usage_triggers(session: AsyncSession, foyer_id: uuid.UUID) -> str | None:
    """Propose the first usage-triggered module whose threshold is met.

    Returns the key of the module proposed, or None. Stops at the first trigger
    that created at least one prompt: one proposal per foyer per pass, the
    5-day floor would refuse the rest anyway. Does not commit.
    """
    for module in get_registry().modules:
        trigger = USAGE_TRIGGERS.get(module.key)
        if trigger is None:
            continue
        reason, condition = trigger
        # Budget and "already on" first: it spares the counting queries for the
        # foyer that has everything, which is most of them in the long run.
        if not await can_propose(session, foyer_id, module.key):
            continue
        if await _proposed_recently(session, foyer_id, module.key):
            continue
        if not await condition(session, foyer_id):
            continue
        if await propose_module(session, foyer_id, module.key, reason=reason) > 0:
            return module.key
    return None


async def daily_usage_triggers_job() -> None:
    """APScheduler job, 03:45 UTC: one evaluation per live foyer, sequentially.

    Same isolation as `daily_retard_job`: a foyer that fails is logged and the
    next one runs. Archived and soft-deleted foyers are skipped.
    """
    try:
        async with async_session() as session:
            foyer_ids = (
                await session.execute(
                    select(Foyer.id).where(
                        Foyer.archived_at.is_(None), Foyer.deleted_at.is_(None)
                    )
                )
            ).scalars().all()
        for foyer_id in foyer_ids:
            try:
                async with async_session() as session:
                    proposed = await evaluate_usage_triggers(session, foyer_id)
                    await session.commit()
                if proposed:
                    logger.info(
                        "usage_trigger_proposed",
                        foyer_id=str(foyer_id),
                        module=proposed,
                    )
            except Exception as exc:  # noqa: BLE001 - one foyer must not stop the rest
                logger.error(
                    "usage_trigger_failed", foyer_id=str(foyer_id), error=str(exc)
                )
    except Exception as exc:  # noqa: BLE001
        logger.error("daily_usage_triggers_job_failed", error=str(exc))
