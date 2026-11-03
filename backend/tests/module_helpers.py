"""Throwaway households for the module proposal and guide tests.

Each test builds the foyer it measures, with the exact age and history it needs,
and removes it afterwards. The shared fixture foyer is never used for a
threshold test: it ages with the database, and a threshold test on a moving
object ends up measuring the calendar (cf. test_a_young_foyer_gets_no_proposal).
"""

from __future__ import annotations

import uuid
from contextlib import asynccontextmanager
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone

from sqlalchemy import delete, select

from app.auth import create_token


@dataclass
class House:
    foyer_id: uuid.UUID
    user_ids: list[uuid.UUID]
    task_id: uuid.UUID

    def headers(self, member: int = 0) -> dict:
        token = create_token(
            str(self.user_ids[member]), str(uuid.uuid4()), foyer_id=str(self.foyer_id)
        )
        return {"Authorization": f"Bearer {token}"}


@asynccontextmanager
async def house(age_days: float = 20, members: int = 2):
    """A foyer `age_days` old with `members` active members and one task."""
    from app.database import async_session
    from app.models.completion import TaskCompletion
    from app.models.foyer import Foyer
    from app.models.sync import ChangeLog
    from app.models.task import Task
    from app.models.user import User
    from app.models.user_foyer import UserFoyer

    foyer_id = uuid.uuid4()
    user_ids = [uuid.uuid4() for _ in range(members)]
    task_id = uuid.uuid4()
    async with async_session() as session:
        session.add(
            Foyer(
                id=foyer_id,
                name="cn-modules",
                created_at=datetime.now(timezone.utc) - timedelta(days=age_days),
            )
        )
        await session.flush()
        for i, uid in enumerate(user_ids):
            session.add(
                User(
                    id=uid,
                    username=f"cn-{uid.hex[:12]}",
                    display_name=f"M{i}",
                    last_active_foyer_id=foyer_id,
                )
            )
        await session.flush()
        for i, uid in enumerate(user_ids):
            session.add(
                UserFoyer(
                    user_id=uid, foyer_id=foyer_id, role="admin" if i == 0 else "member"
                )
            )
        session.add(Task(id=task_id, foyer_id=foyer_id, title="cn task"))
        await session.commit()
    try:
        yield House(foyer_id, user_ids, task_id)
    finally:
        async with async_session() as session:
            await session.execute(
                delete(TaskCompletion).where(TaskCompletion.task_id == task_id)
            )
            # change_log.foyer_id has no ON DELETE: a toggle leaves rows behind.
            await session.execute(delete(ChangeLog).where(ChangeLog.foyer_id == foyer_id))
            await session.execute(delete(Foyer).where(Foyer.id == foyer_id))
            await session.execute(delete(User).where(User.id.in_(user_ids)))
            await session.commit()


async def add_completions(
    h: House, count: int, oldest_days_ago: float = 1, deleted: int = 0
) -> None:
    """`count` live completions, the oldest `oldest_days_ago` old, plus `deleted` dead ones."""
    from app.database import async_session
    from app.models.completion import TaskCompletion

    now = datetime.now(timezone.utc)
    async with async_session() as session:
        for i in range(count + deleted):
            # The first row carries the requested age, the others are more recent.
            when = now - timedelta(days=oldest_days_ago) + timedelta(minutes=i)
            session.add(
                TaskCompletion(
                    task_id=h.task_id,
                    completed_by=h.user_ids[0],
                    completed_at=when,
                    deleted_at=now if i >= count else None,
                )
            )
        await session.commit()


async def add_snapshots(h: House, task_counts: list[int]) -> None:
    """Daily retard snapshots, `task_counts[0]` the OLDEST, the last one yesterday."""
    from app.database import async_session
    from app.models.foyer_retard_snapshot import FoyerRetardSnapshot

    today = date.today()
    async with async_session() as session:
        for i, n in enumerate(task_counts):
            session.add(
                FoyerRetardSnapshot(
                    foyer_id=h.foyer_id,
                    date=today - timedelta(days=len(task_counts) - i),
                    total_minutes=n * 10,
                    task_count=n,
                )
            )
        await session.commit()


async def set_module(
    h: House,
    key: str,
    enabled: bool,
    *,
    user: int | None = None,
    enabled_days_ago: float | None = None,
    updated_by: int | None = None,
) -> None:
    """Write a module_states row: collective when `user` is None, else that member's."""
    from app.database import async_session
    from app.models.module_state import ModuleState

    now = datetime.now(timezone.utc)
    async with async_session() as session:
        session.add(
            ModuleState(
                foyer_id=h.foyer_id,
                user_id=None if user is None else h.user_ids[user],
                module_key=key,
                enabled=enabled,
                # A module switched off keeps its old `enabled_at`: the API only
                # stamps it on an off -> on transition and never clears it.
                enabled_at=(
                    now - timedelta(days=enabled_days_ago)
                    if enabled_days_ago is not None
                    else (now if enabled else None)
                ),
                updated_by_user_id=None if updated_by is None else h.user_ids[updated_by],
            )
        )
        await session.commit()


async def prompts_of(h: House, module_key: str | None = None) -> list:
    """DISCOVER_MODULE prompts of the foyer, optionally for one module."""
    from app.database import async_session
    from app.models.enums import PromptType
    from app.models.user_prompt import UserPrompt

    async with async_session() as session:
        rows = (
            await session.execute(
                select(UserPrompt).where(
                    UserPrompt.foyer_id == h.foyer_id,
                    UserPrompt.type == PromptType.DISCOVER_MODULE,
                )
            )
        ).scalars().all()
    if module_key is None:
        return list(rows)
    return [r for r in rows if r.context.get("module_key") == module_key]


async def run_triggers(h: House) -> str | None:
    """evaluate_usage_triggers in its own committed session."""
    from app.database import async_session
    from app.services.module_usage_triggers import evaluate_usage_triggers

    async with async_session() as session:
        proposed = await evaluate_usage_triggers(session, h.foyer_id)
        await session.commit()
    return proposed
