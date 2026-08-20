"""Resolution of a task's behavioural flags from its contexts.

A task carries several contexts, each with two independent flags. This module
is the single place that turns "which contexts does this task have?" into
"how does this task behave?", so the rule cannot drift between the freeze
service, the task list and the export.

**Resolution is a logical OR.** As soon as one context requires it, the rule
applies. A garden chore also attached to Cleaning keeps accruing during a
holiday, because Garden carries `accrues_when_away` — the grass does not care
that Cleaning does not.

**Freezing is the conjunction of both flags**, and that is the whole point of
having split them. The old model had a single `categories.requires_home`, and
froze whatever carried it:

    freeze = requires_home

That was wrong for the garden — you must be there to mow (so it was masked off
home WiFi, correctly) but the grass grows regardless (so freezing it was
wrong). The rule becomes:

    freeze = OR(requires_presence) AND NOT OR(accrues_when_away)

Read plainly: we only stop the clock for chores that need somebody on site AND
whose need does not build up while nobody is home.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from typing import Iterable

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.context import Context, TaskContext


@dataclass(frozen=True)
class TaskFlags:
    """Behaviour of one task, resolved from all of its contexts."""

    requires_presence: bool = False
    accrues_when_away: bool = False

    @property
    def freezes_when_away(self) -> bool:
        """Should this task's clock stop while nobody is home?"""
        return self.requires_presence and not self.accrues_when_away


#: A task with no context at all. Neutral on purpose: it is neither masked off
#: home WiFi nor frozen. Erring the other way would make a task silently
#: disappear from a list its owner never asked to filter.
NO_FLAGS = TaskFlags()


async def resolve_flags_for_tasks(
    session: AsyncSession, task_ids: Iterable[uuid.UUID]
) -> dict[uuid.UUID, TaskFlags]:
    """Flags per task, in one query.

    Batched deliberately: the callers are list endpoints that already pay one
    round trip per task for completions, and this used to be a second one.
    """
    ids = list(task_ids)
    if not ids:
        return {}

    rows = (await session.execute(
        select(
            TaskContext.task_id,
            Context.requires_presence,
            Context.accrues_when_away,
        )
        .join(Context, Context.id == TaskContext.context_id)
        .where(TaskContext.task_id.in_(ids), Context.deleted_at.is_(None))
    )).all()

    out: dict[uuid.UUID, TaskFlags] = {tid: NO_FLAGS for tid in ids}
    for task_id, presence, accrues in rows:
        actuel = out.get(task_id, NO_FLAGS)
        out[task_id] = TaskFlags(
            requires_presence=actuel.requires_presence or bool(presence),
            accrues_when_away=actuel.accrues_when_away or bool(accrues),
        )
    return out


async def task_ids_requiring_presence(
    session: AsyncSession, task_ids: Iterable[uuid.UUID]
) -> set[uuid.UUID]:
    """Tasks to hide when away from the home WiFi."""
    flags = await resolve_flags_for_tasks(session, task_ids)
    return {tid for tid, f in flags.items() if f.requires_presence}


async def task_ids_freezing_when_away(
    session: AsyncSession, task_ids: Iterable[uuid.UUID]
) -> set[uuid.UUID]:
    """Tasks whose clock stops while the household is empty."""
    flags = await resolve_flags_for_tasks(session, task_ids)
    return {tid for tid, f in flags.items() if f.freezes_when_away}
