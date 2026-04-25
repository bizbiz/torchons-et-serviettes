"""Auto-archivage des taches ponctuelles completees (v0.20).

Une tache sans recurrence (recurrence_rule IS NULL) completee depuis plus
de ARCHIVE_AFTER_DAYS jours passe a is_archived=true. Elle disparait des
lectures liste (GET /tasks, /suggestions, /retard, /maintenance-rate) mais
reste en base (historique preserve pour completions/leaderboard/pace).

Execute par un job quotidien APScheduler configure dans main.py lifespan.
Ecrit une entree change_log par tache archivee pour que la sync Android
propage is_archived=true vers les caches locaux.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import structlog
from sqlalchemy import func, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database import async_session
from app.models.completion import TaskCompletion
from app.models.task import Task
from app.utils.changelog import record_change

logger = structlog.get_logger()


async def run_auto_archive(session: AsyncSession) -> int:
    """Archive les taches ponctuelles completees depuis > ARCHIVE_AFTER_DAYS j.

    Critere precis : la tache n'a PAS de recurrence_rule, elle a au moins une
    completion (sinon il n'y a rien a considerer comme 'terminee'), et sa
    derniere completion non soft-deletee date de plus de ARCHIVE_AFTER_DAYS
    jours. On ignore les taches deja archivees, soft-deletees ou inactives
    (pas de double-tag).

    Retourne le nombre de taches archivees.
    """
    cutoff = datetime.now(timezone.utc) - timedelta(days=settings.archive_after_days)

    # Taches ponctuelles candidates : last completion < cutoff
    last_comp_subq = (
        select(
            TaskCompletion.task_id.label("task_id"),
            func.max(TaskCompletion.completed_at).label("last_completed_at"),
        )
        .where(TaskCompletion.deleted_at.is_(None))
        .group_by(TaskCompletion.task_id)
        .subquery()
    )

    candidates_q = (
        select(Task)
        .join(last_comp_subq, last_comp_subq.c.task_id == Task.id)
        .where(
            Task.recurrence_rule.is_(None),
            Task.is_archived.is_(False),
            Task.deleted_at.is_(None),
            last_comp_subq.c.last_completed_at < cutoff,
        )
    )
    candidates = (await session.execute(candidates_q)).scalars().all()

    if not candidates:
        return 0

    for task in candidates:
        task.is_archived = True
        task.version += 1
        # record_change emet une ligne change_log UPDATE : Android la recoit
        # au prochain /sync et applique is_archived=true dans son cache Room.
        # foyer_id obligatoire pour le scoping v0.7.0.
        await record_change(
            session,
            "tasks",
            task.id,
            "UPDATE",
            changed_by=None,  # job serveur, pas un user
            foyer_id=task.foyer_id,
            new_data={"is_archived": True},
        )

    await session.commit()
    return len(candidates)


async def daily_archive_job() -> None:
    """Point d'entree APScheduler : ouvre une session et lance l'archivage."""
    try:
        async with async_session() as session:
            count = await run_auto_archive(session)
        logger.info(
            "auto_archive_completed",
            tasks_archived=count,
            threshold_days=settings.archive_after_days,
        )
    except Exception as exc:
        # Ne jamais faire crasher le scheduler : on loggue et on retentera demain.
        logger.error("auto_archive_failed", error=str(exc))
