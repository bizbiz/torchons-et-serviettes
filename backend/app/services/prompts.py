"""Generation des prompts dans la queue user_prompts.

Trois sources de prompts :
1. Event-driven (bump) : apres chaque completion, check critere retard.
2. Event-driven (vote) : a la creation d'une tache avec assignments.
3. Cron nightly : expirations de votes > N mois.

Ce module contient la logique pure de generation. Les hooks dans les API
routes appellent ces fonctions apres commit de l'action principale.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

from sqlalchemy import and_, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.completion import TaskCompletion
from app.models.enums import CompletionReason, PromptType
from app.models.foyer_bonus_config import FoyerBonusConfig
from app.models.task import Task
from app.models.task_affinity import TaskAffinity
from app.models.task_assignment import TaskAssignment
from app.models.task_effort import TaskEffort
from app.models.user_foyer import UserFoyer
from app.models.user_prompt import UserPrompt
from app.services.bonus_malus import (
    compute_grace_until,
    load_or_create_bonus_config,
)
from app.services.recurrence import interval_hours


async def generate_bump_prompts(
    session: AsyncSession,
    task: Task,
    foyer_id: uuid.UUID,
) -> None:
    """Verifie si la tache est 'systematiquement en retard' et dispatche un
    prompt BUMP_EFFORT pour chaque user du foyer si le critere est atteint.

    Appele apres chaque POST /complete (solo ou collective). Fire-and-forget
    — les erreurs sont logguees mais ne cassent pas la completion.
    """
    if task.recurrence_rule is None:
        return  # ponctuelles exclues

    hours = interval_hours(task.recurrence_rule)
    if not (hours > 0 and hours != float("inf")):
        return
    interval_days = hours / 24.0

    config = await load_or_create_bonus_config(session, foyer_id)
    now = datetime.now(timezone.utc)

    # 1. Verifier grace period : un prompt BUMP_EFFORT deja dispatche pour
    # cette tache avec grace_until > now ?
    grace_check = await session.execute(
        select(UserPrompt.id).where(
            UserPrompt.foyer_id == foyer_id,
            UserPrompt.type == PromptType.BUMP_EFFORT,
            UserPrompt.grace_until > now,
            # Filtre par task_id dans le JSONB context
            UserPrompt.context["task_id"].astext == str(task.id),
        ).limit(1)
    )
    if grace_check.scalar_one_or_none() is not None:
        return  # encore en grace period

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

    # 6. Inserer un prompt par user du foyer (si pas deja pending)
    foyer_users = await session.execute(
        select(UserFoyer.user_id).where(UserFoyer.foyer_id == foyer_id)
    )
    user_ids = [row[0] for row in foyer_users.all()]

    for uid in user_ids:
        # Check dedup : pas de prompt BUMP pending pour cet user + task
        existing = await session.execute(
            select(UserPrompt.id).where(
                UserPrompt.user_id == uid,
                UserPrompt.foyer_id == foyer_id,
                UserPrompt.type == PromptType.BUMP_EFFORT,
                UserPrompt.resolved_at.is_(None),
                UserPrompt.context["task_id"].astext == str(task.id),
            ).limit(1)
        )
        if existing.scalar_one_or_none() is not None:
            continue  # deja pending

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


async def generate_vote_prompts_for_task(
    session: AsyncSession,
    task: Task,
    foyer_id: uuid.UUID,
    assigned_user_ids: list[uuid.UUID],
    priority: int = 6,
) -> None:
    """Genere des prompts VOTE_RATING pour les users assignes a une tache.

    Appele :
    - A la creation d'une tache avec assignments (priority=6).
    - Par le cron nightly pour les votes expires (priority=3).
    """
    from app.services.recurrence import interval_hours as _ih

    hours = _ih(task.recurrence_rule)
    interval_days = hours / 24.0 if hours > 0 and hours != float("inf") else 0

    context = {
        "task_id": str(task.id),
        "task_title": task.title,
        "interval_days": int(round(interval_days)) if interval_days > 0 else None,
    }

    for uid in assigned_user_ids:
        # Dedup : pas de VOTE_RATING pending pour cet user + task
        existing = await session.execute(
            select(UserPrompt.id).where(
                UserPrompt.user_id == uid,
                UserPrompt.foyer_id == foyer_id,
                UserPrompt.type == PromptType.VOTE_RATING,
                UserPrompt.resolved_at.is_(None),
                UserPrompt.context["task_id"].astext == str(task.id),
            ).limit(1)
        )
        if existing.scalar_one_or_none() is not None:
            continue

        prompt = UserPrompt(
            user_id=uid,
            foyer_id=foyer_id,
            type=PromptType.VOTE_RATING,
            priority=priority,
            context=context,
        )
        session.add(prompt)

    await session.flush()


async def generate_expired_vote_prompts(
    session: AsyncSession,
    foyer_id: uuid.UUID,
    expiry_months: int = 6,
) -> int:
    """Scan les votes expires et genere des prompts VOTE_RATING (priority=3).

    Appele par le cron nightly. Retourne le nombre de prompts generes.
    """
    expiry_cutoff = datetime.now(timezone.utc) - timedelta(days=expiry_months * 30)
    generated = 0

    # Taches actives du foyer avec assignments
    tasks_result = await session.execute(
        select(Task).where(
            Task.foyer_id == foyer_id,
            Task.deleted_at.is_(None),
            Task.is_active.is_(True),
        )
    )
    tasks = tasks_result.scalars().all()

    for task in tasks:
        # Users assignes a cette tache
        assign_result = await session.execute(
            select(TaskAssignment.user_id).where(TaskAssignment.task_id == task.id)
        )
        assigned_ids = [row[0] for row in assign_result.all()]
        if not assigned_ids:
            continue

        for uid in assigned_ids:
            # Verifier si affinite OU effort est expire
            aff_result = await session.execute(
                select(TaskAffinity.updated_at).where(
                    TaskAffinity.task_id == task.id,
                    TaskAffinity.user_id == uid,
                )
            )
            aff_at = aff_result.scalar_one_or_none()

            eff_result = await session.execute(
                select(TaskEffort.updated_at).where(
                    TaskEffort.task_id == task.id,
                    TaskEffort.user_id == uid,
                )
            )
            eff_at = eff_result.scalar_one_or_none()

            # Si les deux sont recents, skip
            if aff_at and aff_at >= expiry_cutoff and eff_at and eff_at >= expiry_cutoff:
                continue

            # Verifier qu'un prompt n'est pas deja pending
            existing = await session.execute(
                select(UserPrompt.id).where(
                    UserPrompt.user_id == uid,
                    UserPrompt.foyer_id == foyer_id,
                    UserPrompt.type == PromptType.VOTE_RATING,
                    UserPrompt.resolved_at.is_(None),
                    UserPrompt.context["task_id"].astext == str(task.id),
                ).limit(1)
            )
            if existing.scalar_one_or_none() is not None:
                continue

            # Cap : max 20 prompts pending par user
            count_result = await session.execute(
                select(func.count(UserPrompt.id)).where(
                    UserPrompt.user_id == uid,
                    UserPrompt.resolved_at.is_(None),
                )
            )
            pending_count = count_result.scalar() or 0
            if pending_count >= 20:
                continue

            prompt = UserPrompt(
                user_id=uid,
                foyer_id=foyer_id,
                type=PromptType.VOTE_RATING,
                priority=3,
                context={
                    "task_id": str(task.id),
                    "task_title": task.title,
                },
            )
            session.add(prompt)
            generated += 1

    await session.flush()
    return generated
