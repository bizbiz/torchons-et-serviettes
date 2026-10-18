"""One completion: PUT /api/completions/{id} (who did it, and when) and its edit history.

Moved out of stats.py on 2026-10-17, unchanged except that a date change now
re-infers the interval of an uncertain task (completions.recompute_interval_of_task).
GET /api/completions/{id}/history followed on 2026-10-18, unchanged except for the solo-task
bound (`visible_to`, app/utils/scoping.py) that both routes now apply.
"""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.completions import recompute_interval_of_task
from app.auth import get_current_user
from app.database import get_session
from app.models.completion import TaskCompletion
from app.models.sync import ChangeLog
from app.models.task import Task
from app.models.user import User
from app.utils.changelog import record_change
from app.utils.errors import LocalizedHTTPException
from app.utils.scoping import assert_active_members, current_foyer_uuid, visible_to

router = APIRouter(prefix="/api", tags=["completions"])


class CompletionUpdate(BaseModel):
    completed_by: UUID | None = None
    completed_at: datetime | None = None


@router.put("/completions/{completion_id}")
async def update_completion(
    completion_id: UUID,
    body: CompletionUpdate,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Modifie une completion (completed_by et/ou completed_at). Scope foyer via JOIN tasks."""
    result = await session.execute(
        select(TaskCompletion)
        .join(Task, Task.id == TaskCompletion.task_id)
        .where(
            TaskCompletion.id == completion_id,
            TaskCompletion.deleted_at.is_(None),
            Task.foyer_id == current_foyer_uuid(current),
            visible_to(Task.id, current),
        )
    )
    comp = result.scalar_one_or_none()
    if not comp:
        raise LocalizedHTTPException(status_code=404, error_code="COMPLETION_NOT_FOUND")

    old_data = {
        "completed_by": str(comp.completed_by),
        "completed_at": comp.completed_at.isoformat(),
    }

    updates = body.model_dump(exclude_unset=True)
    if updates.get("completed_by") is not None:
        await assert_active_members(session, current_foyer_uuid(current), [updates["completed_by"]])
    for key, val in updates.items():
        setattr(comp, key, val)
    comp.version += 1
    if "completed_at" in updates:
        await recompute_interval_of_task(session, comp.task_id)

    await record_change(
        session, "task_completions", comp.id, "UPDATE", current["user_id"],
        foyer_id=current["foyer_id"],
        old_data=old_data,
        new_data={k: str(v) for k, v in updates.items()},
    )
    await session.commit()
    await session.refresh(comp)
    return {
        "id": str(comp.id),
        "task_id": str(comp.task_id),
        "completed_by": str(comp.completed_by),
        "completed_at": comp.completed_at.isoformat(),
        "points_earned": comp.points_earned,
        "notes": comp.notes,
    }


@router.get("/completions/{completion_id}/history")
async def completion_history(
    completion_id: UUID,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Historique des modifications d'une completion. Scope foyer, et tache visible."""
    foyer_uuid = current_foyer_uuid(current)
    hidden_completions = (
        select(TaskCompletion.id)
        .join(Task, Task.id == TaskCompletion.task_id)
        .where(~visible_to(Task.id, current))
    )
    result = await session.execute(
        select(ChangeLog).where(
            ChangeLog.table_name == "task_completions",
            ChangeLog.row_id == completion_id,
            ChangeLog.foyer_id == foyer_uuid,
            ChangeLog.row_id.not_in(hidden_completions),
        ).order_by(ChangeLog.changed_at.desc())
    )
    logs = result.scalars().all()

    # Charger les noms d'utilisateurs
    user_ids = {log.changed_by for log in logs if log.changed_by}
    user_names = {}
    if user_ids:
        users = await session.execute(select(User).where(User.id.in_(user_ids)))
        user_names = {u.id: u.display_name for u in users.scalars().all()}

    return [
        {
            "operation": log.operation,
            "changed_by": user_names.get(log.changed_by, str(log.changed_by)) if log.changed_by else None,
            "changed_at": log.changed_at.isoformat(),
            "old_data": log.old_data,
            "new_data": log.new_data,
        }
        for log in logs
    ]
