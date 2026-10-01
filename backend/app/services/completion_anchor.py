"""Earliest live completion per task: the anchor of an `anniversary` rule.

Only `anniversary` reads it, so only tasks carrying that rule are queried: the
callers (suggestions, /api/retard, retard history, bonus/malus) pay nothing for
the other types. "Live" means `deleted_at IS NULL`: deleting the first
completion hands the anchor over to the next one (docs/contexts.fr.md
§ Récurrence calendaire).
"""

from __future__ import annotations

import uuid
from collections.abc import Iterable
from datetime import datetime

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.task import Task
from app.models.completion import TaskCompletion


def needs_anchor(rule: dict | None) -> bool:
    return isinstance(rule, dict) and rule.get("type") == "anniversary"


async def first_completions_by_task(
    session: AsyncSession, tasks: Iterable[Task]
) -> dict[uuid.UUID, datetime]:
    """{task_id: earliest live completed_at} for the anniversary tasks given."""
    ids = [t.id for t in tasks if needs_anchor(t.recurrence_rule)]
    if not ids:
        return {}
    rows = await session.execute(
        select(TaskCompletion.task_id, func.min(TaskCompletion.completed_at))
        .where(TaskCompletion.task_id.in_(ids), TaskCompletion.deleted_at.is_(None))
        .group_by(TaskCompletion.task_id)
    )
    return {tid: first for tid, first in rows.all()}
