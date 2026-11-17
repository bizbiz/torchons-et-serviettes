"""Completions broken down by MAIN context, for the Stats screens.

Both `/stats/category-frequency` and `/stats/long-term` group completions by the
context at position 0 of their task. The `outerjoin` keeps tasks with no context in
a bucket of their own, so the parts always add up to the completion total; counting
every context would weigh a completion once per attachment.

The bucket's name is sent by the server and shown as is by installed APKs, so it is
translated here from the caller's Accept-Language.
"""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.completion import TaskCompletion
from app.models.context import Context, TaskContext
from app.models.task import Task
from app.schemas.stats_frequency import ContextFrequencyEntry

NO_CONTEXT_LABEL = {"fr": "Sans contexte", "en": "No context"}
TOP_CONTEXTS = 8


def no_context_label(locale: str) -> str:
    """Name of the no-context bucket in `locale`, French when unknown."""
    return NO_CONTEXT_LABEL.get(locale, NO_CONTEXT_LABEL["fr"])


async def context_frequency_entries(
    session: AsyncSession,
    foyer_uuid: UUID,
    user_index: dict[UUID, int],
    cutoff: datetime | None,
    locale: str,
) -> list[ContextFrequencyEntry]:
    """Top contexts by completions, with `counts[i]` for the member at index i."""
    query = (
        select(
            TaskCompletion.completed_by,
            TaskContext.context_id,
            func.count(TaskCompletion.id).label("cnt"),
        )
        .join(Task, Task.id == TaskCompletion.task_id)
        .outerjoin(
            TaskContext,
            (TaskContext.task_id == Task.id) & (TaskContext.position == 0),
        )
        .where(
            TaskCompletion.deleted_at.is_(None),
            Task.foyer_id == foyer_uuid,
        )
    )
    if cutoff is not None:
        query = query.where(TaskCompletion.completed_at >= cutoff)
    query = query.group_by(TaskCompletion.completed_by, TaskContext.context_id)
    rows = (await session.execute(query)).all()

    counts_by_context: dict[UUID | None, list[int]] = {}
    totals: dict[UUID | None, int] = {}
    for completed_by, context_id, cnt in rows:
        if completed_by not in user_index:
            continue
        if context_id not in counts_by_context:
            counts_by_context[context_id] = [0] * len(user_index)
            totals[context_id] = 0
        counts_by_context[context_id][user_index[completed_by]] += int(cnt)
        totals[context_id] += int(cnt)

    names = await _context_names(session, [cid for cid in counts_by_context if cid is not None])
    ordered = sorted(counts_by_context.items(), key=lambda kv: totals[kv[0]], reverse=True)
    return [
        ContextFrequencyEntry(
            context_id=cid,
            context_name=names.get(cid, "?") if cid is not None else no_context_label(locale),
            counts=counts,
            total=totals[cid],
        )
        for cid, counts in ordered[:TOP_CONTEXTS]
    ]


async def long_term_context_rows(
    session: AsyncSession,
    foyer_uuid: UUID,
    since: datetime,
    absent_filter: list,
    effort_subq,
) -> list:
    """(context id, name, completions, points, estimated minutes) per main context."""
    result = await session.execute(
        select(
            Context.id,
            Context.name,
            func.count(TaskCompletion.id),
            func.coalesce(func.sum(TaskCompletion.points_earned), 0),
            func.coalesce(func.sum(effort_subq.c.avg_minutes), 0),
        )
        .select_from(TaskCompletion)
        .join(Task, Task.id == TaskCompletion.task_id)
        .outerjoin(
            TaskContext,
            (TaskContext.task_id == Task.id) & (TaskContext.position == 0),
        )
        .outerjoin(Context, Context.id == TaskContext.context_id)
        .outerjoin(effort_subq, effort_subq.c.task_id == Task.id)
        .where(
            Task.foyer_id == foyer_uuid,
            TaskCompletion.deleted_at.is_(None),
            TaskCompletion.completed_at >= since,
            *absent_filter,
        )
        .group_by(Context.id, Context.name)
        .order_by(func.count(TaskCompletion.id).desc())
    )
    return list(result.all())


async def _context_names(session: AsyncSession, context_ids: list[UUID]) -> dict[UUID, str]:
    if not context_ids:
        return {}
    result = await session.execute(
        select(Context.id, Context.name).where(Context.id.in_(context_ids))
    )
    return {row[0]: row[1] for row in result.all()}
