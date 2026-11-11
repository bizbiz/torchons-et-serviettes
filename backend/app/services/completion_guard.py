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
from datetime import datetime, timedelta

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.completion import TaskCompletion
from app.models.task import Task
from app.services.recurrence import min_completion_gap_seconds


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
