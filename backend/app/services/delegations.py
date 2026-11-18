"""« À faire aujourd'hui »: hand a task to other members, cancel it, settle it.

Spec: docs/plans/a-faire-aujourdhui.md. Writes arrive through /sync (table `task_delegations`,
INSERT to hand a task, DELETE to cancel it), the app's offline queue; reads through
GET /api/delegations. The household switch is the `today` module (modules.yaml, on by default):
off, nothing is written, served or notified.

Rules enforced here, whatever the client sends:
- the possible recipients are the members who SEE the task (app/utils/scoping.py), minus its
  author: one hands a task to someone else, never to oneself;
- one active delegation per task; a second one, sent offline while the first one arrived, is
  acknowledged and not written (the first one wins);
- only the author cancels;
- any completion of the task settles it, whoever records it.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone

from sqlalchemy import func, or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.task import Task
from app.models.task_delegation import TaskDelegation, TaskDelegationRecipient
from app.services.modules import is_module_enabled
from app.utils.changelog import record_change
from app.utils.errors import LocalizedHTTPException
from app.utils.scoping import active_member_ids, hidden_among, visible_to

TODAY_MODULE = "today"
TABLE = "task_delegations"
#: Refused for good by /sync (`rejected`, dropped by the app): no replay makes a recipient valid.
DELEGATION_REFUSALS = frozenset({"DELEGATION_RECIPIENTS_INVALID"})


def _uuid(value) -> uuid.UUID:
    return value if isinstance(value, uuid.UUID) else uuid.UUID(str(value))


def is_active():
    """WHERE clause: the delegation is neither cancelled nor settled."""
    return TaskDelegation.cancelled_at.is_(None), TaskDelegation.settled_at.is_(None)


async def today_enabled(session: AsyncSession, foyer_id) -> bool:
    return await is_module_enabled(session, _uuid(foyer_id), TODAY_MODULE)


async def possible_recipients(session: AsyncSession, task: Task, author_id) -> set[uuid.UUID]:
    """Active members who see the task, the author excepted."""
    author = _uuid(author_id)
    members = await active_member_ids(session, task.foyer_id)
    return {
        m for m in members
        if m != author and str(task.id) not in await hidden_among(session, m, {task.id})
    }


def delegation_payload(row: TaskDelegation, recipient_ids) -> dict:
    """The full row as the app caches it; also the change_log data of an INSERT."""
    return {
        "id": str(row.id),
        "task_id": str(row.task_id),
        "author_id": str(row.author_id),
        "recipient_ids": sorted(str(r) for r in recipient_ids),
        "created_at": row.created_at.isoformat() if row.created_at else None,
    }


async def _done_today(session: AsyncSession, task_id: uuid.UUID) -> bool:
    """A live completion dated today (UTC) or later: the delegation would be settled at once.

    The app hides such a delegation from its cache by the settle rule (DelegationDao), so the
    server does not write it either: both sides agree there is nothing handed.
    """
    from app.models.completion import TaskCompletion

    latest = await session.scalar(
        select(func.max(TaskCompletion.completed_at)).where(
            TaskCompletion.task_id == task_id, TaskCompletion.deleted_at.is_(None),
        )
    )
    return latest is not None and _utc_day(latest) >= _utc_day(datetime.now(timezone.utc))


async def create_delegation(
    session: AsyncSession, delegation_id, task_id, author_id, foyer_id, recipient_ids,
) -> dict | None:
    """Hand `task_id` to `recipient_ids`; the payload written, or None when nothing is.

    Raises LookupError when the task is not a live task of this household the author sees,
    and the 400 `DELEGATION_RECIPIENTS_INVALID` when a recipient may not receive it.
    """
    foyer = _uuid(foyer_id)
    if not await today_enabled(session, foyer):
        return None
    if task_id is None:
        raise LookupError("no task_id")
    task = (
        await session.execute(
            select(Task).where(
                Task.id == _uuid(task_id), Task.foyer_id == foyer,
                Task.deleted_at.is_(None), visible_to(Task.id, author_id),
            )
        )
    ).scalar_one_or_none()
    if task is None:
        raise LookupError("task not found in this household")
    wanted = {_uuid(r) for r in recipient_ids or []}
    if not wanted or wanted - await possible_recipients(session, task, author_id):
        raise LocalizedHTTPException(status_code=400, error_code="DELEGATION_RECIPIENTS_INVALID")
    same_id = await session.get(TaskDelegation, _uuid(delegation_id))
    active_one = await session.scalar(
        select(TaskDelegation.id).where(TaskDelegation.task_id == task.id, *is_active())
    )
    if same_id is not None or active_one is not None or await _done_today(session, task.id):
        return None
    row = TaskDelegation(
        id=_uuid(delegation_id), foyer_id=foyer, task_id=task.id, author_id=_uuid(author_id),
        created_at=datetime.now(timezone.utc),
    )
    session.add(row)
    await session.flush()
    for recipient in wanted:
        session.add(TaskDelegationRecipient(delegation_id=row.id, user_id=recipient))
    await session.flush()
    return delegation_payload(row, wanted)


async def cancel_delegation(session: AsyncSession, delegation_id, author_id, foyer_id) -> dict | None:
    """Cancel an active delegation of its author; None when there is none to cancel.

    Another member's DELETE changes nothing: only the author takes a delegation back.
    """
    row = (
        await session.execute(
            select(TaskDelegation).where(
                TaskDelegation.id == _uuid(delegation_id),
                TaskDelegation.foyer_id == _uuid(foyer_id),
                TaskDelegation.author_id == _uuid(author_id),
                *is_active(),
            )
        )
    ).scalar_one_or_none()
    if row is None:
        return None
    row.cancelled_at = datetime.now(timezone.utc)
    return {"task_id": str(row.task_id), "cancelled_at": row.cancelled_at.isoformat()}


def _utc_day(moment: datetime):
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=timezone.utc)
    return moment.astimezone(timezone.utc).date()


async def settle_task_delegations(
    session: AsyncSession, task_id, changed_by, foyer_id, completed_at: datetime | None = None,
) -> None:
    """A completion of the task ends its active delegation, whoever completed it.

    Only a completion dated on or after the delegation's day (UTC): « done last week », recorded
    today, does not do what was asked for today. The app hides a delegation by the same rule
    from its local cache, without network (DelegationDao). Journalled as an UPDATE, so that the
    other devices drop it from their cache.
    """
    now = datetime.now(timezone.utc)
    done_day = _utc_day(completed_at or now)
    rows = (
        await session.execute(
            select(TaskDelegation).where(TaskDelegation.task_id == _uuid(task_id), *is_active())
        )
    ).scalars().all()
    for row in rows:
        if _utc_day(row.created_at) > done_day:
            continue
        row.settled_at = now
        await record_change(
            session, TABLE, row.id, "UPDATE", str(changed_by), foyer_id=row.foyer_id,
            new_data={"task_id": str(row.task_id), "settled_at": now.isoformat()},
        )


async def recipients_by_delegation(session: AsyncSession, ids: list) -> dict:
    if not ids:
        return {}
    rows = await session.execute(
        select(TaskDelegationRecipient.delegation_id, TaskDelegationRecipient.user_id)
        .where(TaskDelegationRecipient.delegation_id.in_(ids))
    )
    found: dict = {}
    for did, uid in rows.all():
        found.setdefault(did, set()).add(uid)
    return found


async def visible_delegations(session: AsyncSession, foyer_id, user_id) -> list[dict]:
    """Active delegations of the household's live tasks the user sees ([] when switched off)."""
    if not await today_enabled(session, foyer_id):
        return []
    rows = (
        await session.execute(
            select(TaskDelegation)
            .join(Task, Task.id == TaskDelegation.task_id)
            .where(
                TaskDelegation.foyer_id == _uuid(foyer_id), *is_active(),
                Task.deleted_at.is_(None), visible_to(Task.id, user_id),
            )
            .order_by(TaskDelegation.created_at)
        )
    ).scalars().all()
    recipients = await recipients_by_delegation(session, [r.id for r in rows])
    return [delegation_payload(r, recipients.get(r.id, set())) for r in rows]


async def delegated_task_ids(session: AsyncSession, foyer_id, user_id) -> set[uuid.UUID]:
    """Tasks the user handed or received, still to do: out of the user's suggestions."""
    if not await today_enabled(session, foyer_id):
        return set()
    user = _uuid(user_id)
    received = select(TaskDelegationRecipient.delegation_id).where(
        TaskDelegationRecipient.user_id == user
    )
    rows = await session.execute(
        select(TaskDelegation.task_id).where(
            TaskDelegation.foyer_id == _uuid(foyer_id), *is_active(),
            or_(TaskDelegation.author_id == user, TaskDelegation.id.in_(received)),
        )
    )
    return set(rows.scalars().all())
