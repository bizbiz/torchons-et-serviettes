"""Service vote de frequence — upsert + recalcul du consensus.

Toute cadence visible (`tasks.recurrence_rule.every_n_days` en type `interval`)
DOIT etre la moyenne arrondie des `task_frequency_votes`. L'edition directe du
champ `recurrence_rule.every_n_days` via PUT /api/tasks/{id} est bloquee si
des votes existent (cf. tasks.update_task) ; les chemins legitimes qui
introduisent une nouvelle cadence (creation d'une tache, retour au type
interval depuis un autre type) passent par ce helper qui enregistre la saisie
comme un vote de l'user courant et resynchronise le champ.
"""
from __future__ import annotations

from uuid import UUID

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.task import Task
from app.models.task_frequency_vote import TaskFrequencyVote
from app.utils.changelog import record_change


async def upsert_vote_and_sync_consensus(
    session: AsyncSession,
    task: Task,
    user_id: UUID,
    every_n_days: int,
    foyer_id: UUID | str | None = None,
) -> tuple[int | None, int]:
    """Upsert le vote frequence de l'user, recalcule le consensus (moyenne
    arrondie des votes) et l'ecrit dans `task.recurrence_rule` (type=interval).

    Si `foyer_id` est fourni, logge aussi un `task_frequency_votes/VOTE` dans
    change_log (necessaire pour la dedup vote/recurrence dans /api/history :
    sans ce log, le tasks/UPDATE redondant ne peut pas etre identifie comme
    accompagnant un vote). Les callers (set_frequency, create_task,
    update_task retour-interval) doivent passer foyer_id pour que le vote
    soit visible dans l'historique sous la bonne forme.

    Retourne `(consensus_before, consensus_after)` pour alimenter le change_log.
    Ne commit pas — le caller controle la transaction.
    """
    old_rule = dict(task.recurrence_rule) if task.recurrence_rule else None
    consensus_before = None
    if old_rule and isinstance(old_rule.get("every_n_days"), int):
        consensus_before = old_rule["every_n_days"]

    vote_r = await session.execute(
        select(TaskFrequencyVote).where(
            TaskFrequencyVote.task_id == task.id,
            TaskFrequencyVote.user_id == user_id,
        )
    )
    vote = vote_r.scalar_one_or_none()
    if vote:
        vote.every_n_days = every_n_days
        vote.updated_at = func.now()
    else:
        session.add(
            TaskFrequencyVote(
                task_id=task.id, user_id=user_id, every_n_days=every_n_days
            )
        )
    await session.flush()

    avg_r = await session.execute(
        select(func.avg(TaskFrequencyVote.every_n_days)).where(
            TaskFrequencyVote.task_id == task.id
        )
    )
    avg = avg_r.scalar()
    consensus_after = round(avg) if avg is not None else every_n_days

    task.recurrence_rule = {"type": "interval", "every_n_days": consensus_after}
    task.version += 1

    # Log du vote individuel dans change_log si foyer_id fourni. Permet la
    # dedup vote/recurrence cote /api/history (cf. _build_history dans stats).
    if foyer_id is not None:
        await record_change(
            session, "task_frequency_votes", task.id, "VOTE", str(user_id),
            foyer_id=str(foyer_id),
            new_data={
                "every_n_days": every_n_days,
                "consensus_before": consensus_before,
                "consensus_after": consensus_after,
            },
        )

    return consensus_before, consensus_after
