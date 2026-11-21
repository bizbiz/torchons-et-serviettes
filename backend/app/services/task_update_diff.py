"""What a task UPDATE really changes, against the row as it is now.

Both write paths (PUT /tasks/{id} and a /sync UPDATE) call `diff_task_update` and then write,
and journal in change_log, only the fields it returns. Until 2026-11-20 they journalled the
payload as sent: the app sends every field on every save, so a save that changed nothing
was shown as « modified 5 fields » and nothing in the log said what the previous values were.

The comparison is on VALUES, not on how they were sent:
- assignees as a set (their order carries no meaning);
- contexts as « same primary context + same set » (see `same_contexts`);
- the recurrence rule as a dict, null keys dropped, numbers normalised the way they are stored;
- an enum (`tolerance`) by its value.
Strings are compared exactly: nothing in the write paths strips them, so a title that only
differs by a trailing space IS a change (it is stored as sent).
"""

from __future__ import annotations

import enum
import uuid
from dataclasses import dataclass, field

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.context import Context, TaskContext
from app.models.task import Task
from app.models.task_assignment import TaskAssignment
from app.services.recurrence import normalize_recurrence_rule

#: Task columns compared one to one with what the client sent.
SCALAR_FIELDS = ("title", "description", "weight", "tolerance", "is_active", "sort_order")


@dataclass(frozen=True)
class TaskDiff:
    """The fields that change, with their new and their previous value (JSON-safe)."""

    new: dict = field(default_factory=dict)
    old: dict = field(default_factory=dict)


def _plain(value):
    return value.value if isinstance(value, enum.Enum) else value


def _rule_for_comparison(rule):
    """A recurrence rule as stored, without its null keys (older REST writes kept them)."""
    rule = normalize_recurrence_rule(_plain(rule))
    if isinstance(rule, dict):
        return {k: v for k, v in rule.items() if v is not None}
    return rule


def same_contexts(current: list[str], incoming: list[str]) -> bool:
    """True when two ordered context lists mean the same attachment.

    The first one is the PRIMARY context (the one a task is displayed under, TaskContext.position
    0); the others only filter. So a reorder of the tail is no change, but moving another
    context to the front is: dropping it silently would lose the user's choice of primary.
    """
    return current[:1] == incoming[:1] and set(current) == set(incoming)


async def valid_context_ids(
    session: AsyncSession, foyer_id: uuid.UUID, context_ids: list[uuid.UUID],
) -> list[uuid.UUID]:
    """`context_ids` minus the unknown, deleted or out-of-household ones, order kept."""
    if not context_ids:
        return []
    known = set((await session.execute(
        select(Context.id).where(
            Context.id.in_(context_ids),
            Context.foyer_id == foyer_id,
            Context.deleted_at.is_(None),
        )
    )).scalars().all())
    return [cid for cid in context_ids if cid in known]


async def current_context_ids(session: AsyncSession, task_id: uuid.UUID) -> list[str]:
    """The live contexts attached to a task, primary first."""
    rows = await session.execute(
        select(TaskContext.context_id)
        .join(Context, Context.id == TaskContext.context_id)
        .where(TaskContext.task_id == task_id, Context.deleted_at.is_(None))
        .order_by(TaskContext.position)
    )
    return [str(cid) for cid in rows.scalars().all()]


async def current_assignee_ids(session: AsyncSession, task_id: uuid.UUID) -> list[str]:
    rows = await session.execute(
        select(TaskAssignment.user_id).where(TaskAssignment.task_id == task_id)
    )
    return [str(uid) for uid in rows.scalars().all()]


async def diff_task_update(session: AsyncSession, task: Task, incoming: dict) -> TaskDiff:
    """The fields of `incoming` whose value differs from `task`'s now.

    `incoming` holds only keys the caller means to write, from `SCALAR_FIELDS`,
    `recurrence_rule` (dict or None), `assigned_to_ids` and `context_ids` (UUIDs or strings).
    A key whose value is unchanged is absent from the result, as is a key the diff does not
    know. `assigned_to_ids: None` means « not provided ». The task row is not modified.
    """
    new: dict = {}
    old: dict = {}

    for key in SCALAR_FIELDS:
        if key not in incoming:
            continue
        before, after = _plain(getattr(task, key, None)), _plain(incoming[key])
        if before != after:
            new[key], old[key] = after, before

    if "recurrence_rule" in incoming:
        before = _rule_for_comparison(task.recurrence_rule)
        after = _rule_for_comparison(incoming["recurrence_rule"])
        if before != after:
            new["recurrence_rule"] = normalize_recurrence_rule(_plain(incoming["recurrence_rule"]))
            old["recurrence_rule"] = task.recurrence_rule

    if incoming.get("assigned_to_ids") is not None:
        before = await current_assignee_ids(session, task.id)
        after = [str(u) for u in incoming["assigned_to_ids"]]
        if set(before) != set(after):
            new["assigned_to_ids"], old["assigned_to_ids"] = after, before

    if "context_ids" in incoming:
        wanted = [uuid.UUID(str(c)) for c in (incoming["context_ids"] or [])]
        # What the write would keep: an unknown id is dropped on write, so it must not make
        # every save look like a change.
        kept = [str(c) for c in await valid_context_ids(session, task.foyer_id, wanted)]
        before = await current_context_ids(session, task.id)
        if not same_contexts(before, kept):
            new["context_ids"], old["context_ids"] = kept, before

    return TaskDiff(new=new, old=old)
