"""Generation des prompts dans la queue user_prompts.

Source of prompts generated here: BUMP_EFFORT, after each completion
(chronic lateness check). Rating renewal is no longer a prompt: the home
screen asks for it through GET /api/pending-affinity.

Ce module contient la logique pure de generation. Les hooks dans les API
routes appellent ces fonctions apres commit de l'action principale.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

from sqlalchemy import and_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.completion import TaskCompletion
from app.models.enums import CompletionReason, PromptType
from app.models.foyer_bonus_config import FoyerBonusConfig
from app.models.task import Task
from app.models.user_foyer import UserFoyer
from app.models.user_prompt import UserPrompt
from app.services.bonus_malus import (
    compute_grace_until,
    load_or_create_bonus_config,
)
from app.services.module_discovery import propose_module
from app.services.modules import is_module_enabled
from app.services.recurrence import interval_hours, is_uncertain


async def bump_after_completion(
    session: AsyncSession, task: Task, foyer_id: uuid.UUID, reasons: list,
) -> None:
    """Run the chronic-lateness check after a completion; never raises.

    `reasons` are those of the completion rows just written: one late row is
    what lets a member who answered « Plus tard » be asked again.
    """
    try:
        late = any(r == CompletionReason.BONUS_RETARD for r in reasons)
        await generate_bump_prompts(session, task, foyer_id, just_completed_late=late)
        await session.commit()
    except Exception:
        pass  # never break the completion


async def _members_to_ask(
    session: AsyncSession, task: Task, foyer_id: uuid.UUID, now: datetime,
    just_completed_late: bool,
) -> list[uuid.UUID]:
    """Active members who may receive a BUMP_EFFORT for this task now.

    The grace period is per member (grill of 2026-11-19), read on that member's
    LATEST BUMP_EFFORT for the task:
    - none, or its grace is over: may be asked;
    - still pending: not asked twice;
    - answered « Plus tard » (context.postponed): asked again on the next LATE
      completion, whoever made it, without waiting for the grace;
    - re-voted or « Ignorer »: keeps its grace.
    """
    members = (await session.execute(
        select(UserFoyer.user_id).where(
            UserFoyer.foyer_id == foyer_id, UserFoyer.left_at.is_(None),
        )
    )).scalars().all()
    rows = (await session.execute(
        select(UserPrompt).where(
            UserPrompt.foyer_id == foyer_id,
            UserPrompt.type == PromptType.BUMP_EFFORT,
            UserPrompt.context["task_id"].astext == str(task.id),
        ).order_by(UserPrompt.created_at)
    )).scalars().all()
    latest = {p.user_id: p for p in rows}  # ordered by date: the last one wins

    eligible = []
    for uid in members:
        prev = latest.get(uid)
        if prev is None:
            eligible.append(uid)
        elif prev.resolved_at is None:
            continue
        elif (prev.context or {}).get("postponed"):
            if just_completed_late:
                eligible.append(uid)
        elif prev.grace_until is None or prev.grace_until <= now:
            eligible.append(uid)
    return eligible


async def generate_bump_prompts(
    session: AsyncSession,
    task: Task,
    foyer_id: uuid.UUID,
    just_completed_late: bool = False,
) -> None:
    """Verifie si la tache est 'systematiquement en retard' et dispatche un
    prompt BUMP_EFFORT pour chaque membre qui peut la recevoir (cf
    `_members_to_ask`) si le critere est atteint.

    Appele apres chaque POST /complete (solo ou collective), via
    `bump_after_completion`.
    """
    # P4 — le module peut etre eteint. On ne sort PAS tout de suite pour
    # autant : le critere "systematiquement en retard" est exactement le signal
    # qui justifie de proposer l'ajustement automatique. Le calculer puis le
    # jeter reviendrait a ne jamais pouvoir proposer la fonctionnalite aux
    # foyers qui en ont le plus besoin. On calcule, puis on aiguille — prompt
    # de travail si le module est actif, proposition sinon.
    module_on = await is_module_enabled(session, foyer_id, "auto_adjust")

    if task.recurrence_rule is None:
        return  # ponctuelles exclues
    if is_uncertain(task.recurrence_rule):
        return  # uncertain : pas de bump (pas de notion d'"en retard", cf Q2)

    hours = interval_hours(task.recurrence_rule)
    if not (hours > 0 and hours != float("inf")):
        return
    interval_days = hours / 24.0

    config = await load_or_create_bonus_config(session, foyer_id)
    now = datetime.now(timezone.utc)

    # 1. Grace period, now per member (was per task until 2026-11-19).
    user_ids = await _members_to_ask(session, task, foyer_id, now, just_completed_late)
    if not user_ids:
        return

    # 2. Fenetre d'analyse Y = clamp(K * interval, y_min, y_max)
    y_raw = config.bump_interval_factor_k * interval_days
    y_days = max(config.bump_y_min_days, min(config.bump_y_max_days, int(round(y_raw))))
    window_start = now - timedelta(days=y_days)

    # 3. Charger les completions dans la fenetre
    comp_result = await session.execute(
        select(TaskCompletion.reason).where(
            TaskCompletion.task_id == task.id,
            TaskCompletion.deleted_at.is_(None),
            TaskCompletion.completed_at >= window_start,
        )
    )
    completions = comp_result.scalars().all()
    total = len(completions)
    if total < config.bump_z_min:
        return  # pas assez de data

    # 4. Calculer la fraction en zone bonus
    bonus_count = sum(1 for r in completions if r == CompletionReason.BONUS_RETARD)
    ratio = bonus_count / total
    if ratio < config.bump_x_ratio:
        return  # pas assez de retard

    # 5. Construire le snapshot des completions recentes pour le modal
    recent_result = await session.execute(
        select(
            TaskCompletion.completed_by,
            TaskCompletion.completed_at,
            TaskCompletion.multiplier,
            TaskCompletion.reason,
        ).where(
            TaskCompletion.task_id == task.id,
            TaskCompletion.deleted_at.is_(None),
            TaskCompletion.completed_at >= window_start,
        ).order_by(TaskCompletion.completed_at.desc())
        .limit(10)
    )
    recent_rows = recent_result.all()
    completions_snapshot = []
    for row in recent_rows:
        completions_snapshot.append({
            "completed_by": str(row.completed_by),
            "completed_at": row.completed_at.isoformat() if row.completed_at else None,
            "multiplier": row.multiplier,
            "reason": row.reason.value if row.reason else "NEUTRAL",
        })

    grace_until = compute_grace_until(
        interval_days=interval_days,
        triggered_at=now,
        config=config,
    )

    context = {
        "task_id": str(task.id),
        "task_title": task.title,
        "interval_days": int(round(interval_days)),
        "window_days": y_days,
        "bonus_ratio": round(ratio, 2),
        "completions": completions_snapshot,
    }

    # Critere atteint. Module eteint : on propose de l'activer plutot que de
    # generer un prompt de travail que personne n'a demande.
    if not module_on:
        await propose_module(
            session,
            foyer_id,
            "auto_adjust",
            reason="task_chronically_late",
        )
        await session.flush()
        return

    # 6. One prompt per member selected in step 1 (active, none pending).
    for uid in user_ids:
        prompt = UserPrompt(
            user_id=uid,
            foyer_id=foyer_id,
            type=PromptType.BUMP_EFFORT,
            priority=8,
            context=context,
            grace_until=grace_until,
        )
        session.add(prompt)

    await session.flush()
