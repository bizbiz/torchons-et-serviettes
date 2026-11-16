"""What happens to a member's tasks when they leave the household, come back, or are purged.

Rule set by Quentin on 2026-11-02:
- A SHARED task (other assignees remain) simply loses the member who left. Keeping them made
  the task impossible to edit: the form shows active members only, the app sends the whole
  `assigned_to_ids` back, and the server refuses the op for good (`USERS_OUTSIDE_FOYER_FORMAT`),
  so the edit was lost (seen on the emulator on 2026-11-02).
- A SOLO task (they were its only assignee) stays theirs, deactivated (`is_active = false`) and
  marked `suspended_at`. It stays invisible to the others, as any solo task
  (utils/scoping.py). If they come back, it comes back with them.
- When they come back (rule set on 2026-11-03), they are put back on the shared tasks they
  were removed from at THAT departure, if the task still exists. The departure marker in
  change_log carries the departure time, which is how a return finds them without a table.
- When the account is hard-deleted (`services/cleanup.py`, 30 days after `DELETE /me`), the
  tasks whose only assignees are purged accounts are deleted with it. Without this, the
  assignment rows vanish by CASCADE and the task, with no assignee left, becomes visible to
  the whole household.

Every change is written to change_log, so the other members' phones pull it.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone

from sqlalchemy import DateTime, delete, exists, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.completion import TaskCompletion
from app.models.sync import ChangeLog
from app.models.task import Task
from app.models.task_assignment import TaskAssignment
from app.utils.changelog import record_change


async def _assignees(session: AsyncSession, task_id: uuid.UUID) -> list[uuid.UUID]:
    rows = await session.execute(
        select(TaskAssignment.user_id).where(TaskAssignment.task_id == task_id)
    )
    return list(rows.scalars().all())


MEMBER_LEFT_REASON = "member_left"


async def _log(
    session: AsyncSession, task: Task, user_id: uuid.UUID, new_data: dict, marker: dict | None = None,
) -> None:
    task.version += 1
    # The marker goes to old_data: /sync applies new_data only (api/sync.py), so it reaches the
    # task history (api/tasks.py::get_task_history) and never a phone's task row.
    await record_change(
        session, "tasks", task.id, "UPDATE", str(user_id),
        foyer_id=task.foyer_id, old_data=marker, new_data=new_data,
    )


async def detach_member_from_tasks(
    session: AsyncSession, user_id: uuid.UUID, foyer_id: uuid.UUID, left_at: datetime | None = None,
) -> None:
    """Called when `user_id` leaves `foyer_id` (services/lifecycle.py::soft_leave_foyer).

    `left_at` must be the value written to `user_foyers.left_at`: a return matches the
    departure markers on it (restore_shared_assignments).
    """
    now = left_at or datetime.now(timezone.utc)
    marker = {"reason": MEMBER_LEFT_REASON, "left_at": now.isoformat()}
    tasks = (await session.execute(
        select(Task)
        .join(TaskAssignment, TaskAssignment.task_id == Task.id)
        .where(
            Task.foyer_id == foyer_id,
            Task.deleted_at.is_(None),
            TaskAssignment.user_id == user_id,
        )
    )).scalars().all()
    for task in tasks:
        others = [u for u in await _assignees(session, task.id) if u != user_id]
        if others:
            await session.execute(delete(TaskAssignment).where(
                TaskAssignment.task_id == task.id, TaskAssignment.user_id == user_id,
            ))
            await _log(
                session, task, user_id, {"assigned_to_ids": [str(u) for u in others]}, marker,
            )
        elif task.is_active:
            # Already inactive: left as it is, unmarked, so a return does not revive it.
            task.is_active = False
            task.suspended_at = now
            await _log(session, task, user_id, {"is_active": False}, marker)


async def restore_shared_assignments(
    session: AsyncSession, user_id: uuid.UUID, foyer_id: uuid.UUID, left_at: datetime,
) -> None:
    """Called when `user_id` comes back: put them back on the shared tasks they left at `left_at`.

    Only the departure that ends now counts: a task they were removed from by hand after an
    earlier return stays as it is. A task deleted since, or that they are already on, is skipped.
    Departures recorded before 2026-11-03 carry no time and are not restored.
    """
    task_ids = (await session.execute(
        select(ChangeLog.row_id).distinct().where(
            ChangeLog.table_name == "tasks",
            ChangeLog.foyer_id == foyer_id,
            ChangeLog.changed_by == user_id,
            ChangeLog.old_data["reason"].astext == MEMBER_LEFT_REASON,
            ChangeLog.old_data["left_at"].astext.cast(DateTime(timezone=True)) == left_at,
            ChangeLog.new_data.has_key("assigned_to_ids"),
        )
    )).scalars().all()
    for task_id in task_ids:
        task = await session.get(Task, task_id)
        if task is None or task.deleted_at is not None or task.foyer_id != foyer_id:
            continue
        assignees = await _assignees(session, task.id)
        if user_id in assignees:
            continue
        session.add(TaskAssignment(task_id=task.id, user_id=user_id))
        await _log(session, task, user_id, {"assigned_to_ids": [str(u) for u in assignees + [user_id]]})


async def reactivate_suspended_tasks(
    session: AsyncSession, user_id: uuid.UUID, foyer_id: uuid.UUID,
) -> None:
    """Called when `user_id` becomes an active member of `foyer_id` again."""
    tasks = (await session.execute(
        select(Task)
        .join(TaskAssignment, TaskAssignment.task_id == Task.id)
        .where(
            Task.foyer_id == foyer_id,
            Task.deleted_at.is_(None),
            Task.suspended_at.is_not(None),
            TaskAssignment.user_id == user_id,
        )
    )).scalars().all()
    for task in tasks:
        if await _assignees(session, task.id) != [user_id]:
            continue
        task.is_active = True
        task.suspended_at = None
        await _log(session, task, user_id, {"is_active": True})


async def delete_tasks_only_assigned_to(session: AsyncSession, user_ids) -> int:
    """Hard-delete the tasks whose every assignee is in `user_ids`. Returns the count.

    Must run BEFORE the users are deleted: afterwards the assignment rows are gone by CASCADE
    and nothing tells these tasks apart from tasks that never had an assignee.

    task_completions.task_id has no ON DELETE: until 2026-11-16 a solo task done even once
    blocked this delete and failed the whole nightly job. Its completions by these accounts go
    with it. A task someone ELSE also completed is kept (still theirs, suspended, invisible):
    another member's completion is never deleted here.
    """
    ids = list(user_ids)
    if not ids:
        return 0
    only_theirs = (
        select(TaskAssignment.task_id)
        .group_by(TaskAssignment.task_id)
        .having(func.count().filter(TaskAssignment.user_id.not_in(ids)) == 0)
    )
    done_by_others = exists().where(
        TaskCompletion.task_id == Task.id, TaskCompletion.completed_by.not_in(ids),
    )
    doomed = (await session.execute(
        select(Task.id).where(Task.id.in_(only_theirs), ~done_by_others)
    )).scalars().all()
    if not doomed:
        return 0
    await session.execute(delete(TaskCompletion).where(TaskCompletion.task_id.in_(doomed)))
    result = await session.execute(delete(Task).where(Task.id.in_(doomed)))
    return result.rowcount or 0
