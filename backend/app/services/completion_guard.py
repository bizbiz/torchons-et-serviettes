"""Anti-duplicate rule for completions arriving by /sync.

POST /tasks/{id}/complete refuses (409 COMPLETION_DUPLICATE) a completion of the same task by
the same member inside `min_completion_gap_seconds` (1 h, or 24 h for a weekly-or-rarer task).
/sync, which the app and the widget use through the offline queue, did not: on 2026-11-11
Quentin tapped the widget's ✓ three times in 6 s on an already-green task and the household
got three completions. /sync now drops such a duplicate instead of writing it; the op is still
acknowledged, so the client's queue never blocks on it.

The window is centred on the completion's own date, not on « now »: a completion dated
afterwards (« Déjà fait ») is a duplicate of one recorded near THAT date.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

from sqlalchemy import desc, select, text
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.completion import TaskCompletion
from app.models.task import Task
from app.services.recurrence import min_completion_gap_seconds
from app.utils.errors import LocalizedHTTPException


async def lock_completion_slot(session: AsyncSession, task_id: uuid.UUID, completed_by: uuid.UUID) -> None:
    """Serialise the « duplicate? then write » step of one (task, member) across transactions.

    Without it, two requests carrying DIFFERENT ops for the same task and member each check in
    their own transaction before the other commits, both find nothing, and both write.

    Call it right BEFORE the duplicate check, on every path that writes a completion (/sync and
    POST /tasks/{id}/complete). The lock is a Postgres transaction-level advisory lock: it
    is released when the OUTER transaction ends (commit or rollback), not when a /sync
    per-op SAVEPOINT is released. That is intended: the completion row becomes visible to the
    next holder exactly when the lock is released. A second request therefore waits here, then
    (READ COMMITTED: each statement sees what was committed meanwhile) finds the first
    completion and drops or refuses itself.

    The key is hashed by Postgres to a bigint; a hash collision only makes two unrelated
    (task, member) pairs wait for each other, never skips a check.
    """
    await session.execute(
        text("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))"),
        {"key": f"completion:{task_id}:{completed_by}"},
    )


async def refuse_recent_duplicate(session: AsyncSession, task: Task, completed_by: uuid.UUID) -> None:
    """POST /tasks/{id}/complete: 409 COMPLETION_DUPLICATE when `completed_by` already has a live
    completion of `task` in the window (24h for a weekly-or-rarer task, 1h otherwise).

    Takes the same lock as /sync, so the check and the insert that follow in the caller are
    atomic against a concurrent request for the same task and member.
    """
    await lock_completion_slot(session, task.id, completed_by)
    gap_seconds = min_completion_gap_seconds(task.recurrence_rule, task.inferred_interval_days)
    window_start = datetime.now(timezone.utc) - timedelta(seconds=gap_seconds)
    recent = await session.execute(
        select(TaskCompletion)
        .where(
            TaskCompletion.task_id == task.id,
            TaskCompletion.completed_by == completed_by,
            TaskCompletion.deleted_at.is_(None),
            TaskCompletion.completed_at >= window_start,
        )
        .order_by(desc(TaskCompletion.completed_at))
        .limit(1)
    )
    last = recent.scalar_one_or_none()
    if last is not None:
        elapsed = datetime.now(timezone.utc) - last.completed_at
        retry_at = last.completed_at + timedelta(seconds=gap_seconds)
        elapsed_min = int(elapsed.total_seconds() // 60)
        # Localised 409. Formerly a detail dict {message, retry_at}, turned into a localised
        # string by the global handler; the client only used it for a toast.
        raise LocalizedHTTPException(
            status_code=409,
            error_code="COMPLETION_DUPLICATE",
            format_args={"elapsed_min": elapsed_min, "retry_at": retry_at.isoformat()},
        )


async def refuse_collective_duplicate(
    session: AsyncSession, task: Task, participants: list[uuid.UUID],
) -> None:
    """POST /tasks/{id}/complete-collective: 409 COMPLETION_COLLECTIVE_DUPLICATE when AT LEAST
    ONE participant already has a live completion of `task` in the window (all or nothing).

    Locks every participant's slot first, in a fixed order so that two collectives sharing
    members never wait on each other in a cycle.
    """
    for member in sorted(participants, key=str):
        await lock_completion_slot(session, task.id, member)
    gap_seconds = min_completion_gap_seconds(task.recurrence_rule, task.inferred_interval_days)
    window_start = datetime.now(timezone.utc) - timedelta(seconds=gap_seconds)
    conflict_result = await session.execute(
        select(TaskCompletion.id)
        .where(
            TaskCompletion.task_id == task.id,
            TaskCompletion.completed_by.in_(participants),
            TaskCompletion.deleted_at.is_(None),
            TaskCompletion.completed_at >= window_start,
        )
        .limit(1)
    )
    if conflict_result.first() is not None:
        # Localised 409. The old detail was a dict {message, conflicts, gap_seconds}; the client
        # only used the message.
        raise LocalizedHTTPException(status_code=409, error_code="COMPLETION_COLLECTIVE_DUPLICATE")


async def duplicates_recent_completion(
    session: AsyncSession, task: Task, completed_by: uuid.UUID, completed_at: datetime,
) -> bool:
    """True when `completed_by` already has a live completion of `task` near `completed_at`."""
    gap = timedelta(seconds=min_completion_gap_seconds(task.recurrence_rule, task.inferred_interval_days))
    found = await session.execute(
        select(TaskCompletion.id).where(
            TaskCompletion.task_id == task.id,
            TaskCompletion.completed_by == completed_by,
            TaskCompletion.deleted_at.is_(None),
            TaskCompletion.completed_at > completed_at - gap,
            TaskCompletion.completed_at < completed_at + gap,
        ).limit(1)
    )
    return found.first() is not None
