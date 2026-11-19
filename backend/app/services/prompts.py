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

    # 6. Inserer un prompt par membre ACTIF du foyer (si pas deja pending) :
    # un ancien membre recevait aussi la question.
    foyer_users = await session.execute(
        select(UserFoyer.user_id).where(
            UserFoyer.foyer_id == foyer_id, UserFoyer.left_at.is_(None),
        )
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
