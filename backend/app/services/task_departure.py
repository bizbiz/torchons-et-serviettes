"""What happens to a member's tasks when they leave the household, come back, or are purged.

Rule set by Quentin on 2026-11-02:
- A SHARED task (other assignees remain) simply loses the member who left. Keeping them made
  the task impossible to edit: the form shows active members only, the app sends the whole
  `assigned_to_ids` back, and the server refuses the op for good (`USERS_OUTSIDE_FOYER_FORMAT`),
  so the edit was lost (seen on the emulator on 2026-11-02).
- A SOLO task (they were its only assignee) stays theirs, deactivated (`is_active = false`) and
  marked `suspended_at`. It stays invisible to the others, as any solo task
  (utils/scoping.py). If they come back, it comes back with them.
- When the account is hard-deleted (`services/cleanup.py`, 30 days after `DELETE /me`), the
  tasks whose only assignees are purged accounts are deleted with it. Without this, the
  assignment rows vanish by CASCADE and the task, with no assignee left, becomes visible to
  the whole household.

Every change is written to change_log, so the other members' phones pull it.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone

from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.task import Task
from app.models.task_assignment import TaskAssignment
from app.utils.changelog import record_change


async def _assignees(session: AsyncSession, task_id: uuid.UUID) -> list[uuid.UUID]:
    rows = await session.execute(
        select(TaskAssignment.user_id).where(TaskAssignment.task_id == task_id)
    )
    return list(rows.scalars().all())


async def _log(session: AsyncSession, task: Task, user_id: uuid.UUID, new_data: dict) -> None:
    task.version += 1
    await record_change(
        session, "tasks", task.id, "UPDATE", str(user_id),
        foyer_id=task.foyer_id, new_data=new_data,
    )


async def detach_member_from_tasks(
    session: AsyncSession, user_id: uuid.UUID, foyer_id: uuid.UUID,
) -> None:
    """Called when `user_id` leaves `foyer_id` (services/lifecycle.py::soft_leave_foyer)."""
    tasks = (await session.execute(
        select(Task)
        .join(TaskAssignment, TaskAssignment.task_id == Task.id)
        .where(
            Task.foyer_id == foyer_id,
            Task.deleted_at.is_(None),
            TaskAssignment.user_id == user_id,
        )
    )).scalars().all()
    now = datetime.now(timezone.utc)
    for task in tasks:
        others = [u for u in await _assignees(session, task.id) if u != user_id]
        if others:
            await session.execute(delete(TaskAssignment).where(
                TaskAssignment.task_id == task.id, TaskAssignment.user_id == user_id,
            ))
            await _log(session, task, user_id, {"assigned_to_ids": [str(u) for u in others]})
        elif task.is_active:
            # Already inactive: left as it is, unmarked, so a return does not revive it.
            task.is_active = False
            task.suspended_at = now
            await _log(session, task, user_id, {"is_active": False})


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
    """
    ids = list(user_ids)
    if not ids:
        return 0
    only_theirs = (
        select(TaskAssignment.task_id)
        .group_by(TaskAssignment.task_id)
        .having(func.count().filter(TaskAssignment.user_id.not_in(ids)) == 0)
    )
    result = await session.execute(delete(Task).where(Task.id.in_(only_theirs)))
    return result.rowcount or 0
