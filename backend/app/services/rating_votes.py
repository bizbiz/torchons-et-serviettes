"""A member's effort and affinity votes on a task: one atomic write, one history line.

The lock is per TASK, not per (task, member): the consensus (`task.weight`, the affinity mean)
is recomputed from every member's vote, and two members voting together each computed it
before the other's vote was committed, so the last commit left a weight that matched neither
mean (measured 2026-11-20: votes 3 and 7 ended at weight 7, not 5).

The app's sync worker can resend a request that is still in flight. On the beta (2026-11-20)
`PUT /api/tasks/{id}/effort` answered 500 on `task_efforts_pkey` when a queued vote was
replayed that way: both copies selected no row, both inserted. Both writers here therefore
(1) take a per-task lock, so the second copy waits for the first one's commit, and
(2) write with `INSERT ... ON CONFLICT DO UPDATE`, so no path can fail on the primary key.

A replay of the value already stored adds no history line: it would read as a second,
identical vote. The row's `updated_at` is still touched ("reviewed again": it drives the
rating cadence and expiry).

Neither function commits: the caller owns the transaction.
"""

from __future__ import annotations

from uuid import UUID

from sqlalchemy import func, select
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.task import Task
from app.models.task_affinity import TaskAffinity
from app.models.task_effort import TaskEffort
from app.utils.changelog import record_change
from app.utils.row_lock import lock_key


async def record_effort_vote(
    session: AsyncSession, task_id: UUID, user_id: UUID, foyer_id: str | UUID, points: int
) -> None:
    """Set the member's effort (1pt = 5min), refresh `task.weight`, log the VOTE if it changed."""
    await lock_key(session, f"votes:effort:{task_id}")

    # Consensus before: the task's current weight (already round(avg)).
    task = (await session.execute(select(Task).where(Task.id == task_id))).scalar_one_or_none()
    consensus_before = task.weight if task else None

    previous = (await session.execute(
        select(TaskEffort.points).where(
            TaskEffort.task_id == task_id, TaskEffort.user_id == user_id
        )
    )).scalar_one_or_none()
    await session.execute(
        pg_insert(TaskEffort)
        .values(task_id=task_id, user_id=user_id, points=points)
        .on_conflict_do_update(
            index_elements=[TaskEffort.task_id, TaskEffort.user_id],
            set_={"points": points, "updated_at": func.now()},
        )
    )

    # Consensus after: the task's weight becomes the rounded mean of every member's vote.
    avg = (await session.execute(
        select(func.avg(TaskEffort.points)).where(TaskEffort.task_id == task_id)
    )).scalar()
    consensus_after = round(avg) if avg is not None else None
    if consensus_after is not None and task:
        task.weight = consensus_after

    if previous != points:
        await record_change(
            session, "task_efforts", task_id, "VOTE", str(user_id), foyer_id=foyer_id,
            new_data={
                "points": points,
                "consensus_before": consensus_before,
                "consensus_after": consensus_after,
            },
        )


async def record_affinity_vote(
    session: AsyncSession, task_id: UUID, user_id: UUID, foyer_id: str | UUID, score: int
) -> None:
    """Set the member's affinity (1-10), log the VOTE if it changed.

    The history line is keyed by the task id (table_name='task_affinities'), the primary key
    being composite.
    """
    await lock_key(session, f"votes:affinity:{task_id}")

    # Consensus before: rounded mean of the existing scores.
    cb_raw = (await session.execute(
        select(func.avg(TaskAffinity.score)).where(TaskAffinity.task_id == task_id)
    )).scalar()
    consensus_before = round(cb_raw) if cb_raw is not None else None

    previous = (await session.execute(
        select(TaskAffinity.score).where(
            TaskAffinity.task_id == task_id, TaskAffinity.user_id == user_id
        )
    )).scalar_one_or_none()
    await session.execute(
        pg_insert(TaskAffinity)
        .values(task_id=task_id, user_id=user_id, score=score)
        .on_conflict_do_update(
            index_elements=[TaskAffinity.task_id, TaskAffinity.user_id],
            set_={"score": score, "updated_at": func.now()},
        )
    )

    ca_raw = (await session.execute(
        select(func.avg(TaskAffinity.score)).where(TaskAffinity.task_id == task_id)
    )).scalar()
    consensus_after = round(ca_raw) if ca_raw is not None else None

    if previous != score:
        await record_change(
            session, "task_affinities", task_id, "VOTE", str(user_id), foyer_id=foyer_id,
            new_data={
                "score": score,
                "consensus_before": consensus_before,
                "consensus_after": consensus_after,
            },
        )
