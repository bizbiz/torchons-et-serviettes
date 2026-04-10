"""Routes completions — marquer une tache comme faite."""

from __future__ import annotations

from datetime import datetime, timezone
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import func, select, desc
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.models.completion import TaskCompletion
from app.models.fair_share import FairShareConfig
from app.models.task import Task
from app.models.task_assignment import TaskAssignment
from app.models.task_effort import TaskEffort
from app.models.task_in_progress import TaskInProgress
from app.schemas.completion import CompletionCreate, CompletionResponse
from app.utils.changelog import record_change

router = APIRouter(tags=["completions"])


def _safe_uuid(value: str | None) -> UUID | None:
    """Convertit en UUID si possible, sinon retourne None."""
    if not value:
        return None
    try:
        return UUID(value)
    except (ValueError, AttributeError):
        return None


@router.post("/api/tasks/{task_id}/complete", response_model=CompletionResponse, status_code=201)
async def complete_task(
    task_id: UUID,
    body: CompletionCreate = CompletionCreate(),
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Marque une tache comme completee."""
    result = await session.execute(
        select(Task).where(Task.id == task_id, Task.deleted_at.is_(None))
    )
    task = result.scalar_one_or_none()
    if not task:
        raise HTTPException(status_code=404, detail="Tache introuvable")

    completed_by = body.completed_by or UUID(current["user_id"])

    # Points : moyenne des efforts si disponible, sinon weight. 0 pour taches perso.
    assign_result = await session.execute(
        select(func.count(TaskAssignment.user_id)).where(TaskAssignment.task_id == task_id)
    )
    num_assigned = assign_result.scalar() or 1
    if num_assigned <= 1:
        points = 0
    else:
        avg_result = await session.execute(
            select(func.avg(TaskEffort.points)).where(TaskEffort.task_id == task_id)
        )
        avg_effort = avg_result.scalar()
        points = round(avg_effort) if avg_effort else task.weight

    completion = TaskCompletion(
        task_id=task_id,
        completed_by=completed_by,
        points_earned=points,
        notes=body.notes,
        client_id=_safe_uuid(current.get("device_id")),
    )
    if body.completed_at:
        completion.completed_at = body.completed_at
    session.add(completion)
    await session.flush()

    # Supprimer la tache "en cours" si elle existe
    from sqlalchemy import delete
    await session.execute(
        delete(TaskInProgress).where(TaskInProgress.task_id == task_id)
    )

    # Auto-desactiver les taches ponctuelles (sans recurrence)
    if task.recurrence_rule is None:
        task.is_active = False
        task.version += 1

    await record_change(session, "task_completions", completion.id, "INSERT", current["user_id"])
    await session.commit()
    await session.refresh(completion)

    # Envoyer notification push aux autres utilisateurs assignes
    from app.services.push import send_task_completed_notification
    await send_task_completed_notification(session, current["user_id"], str(task_id), task.title)

    return completion


@router.delete("/api/tasks/{task_id}/completions/{completion_id}")
async def undo_completion(
    task_id: UUID,
    completion_id: UUID,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Annule une completion (soft delete)."""
    result = await session.execute(
        select(TaskCompletion).where(
            TaskCompletion.id == completion_id,
            TaskCompletion.task_id == task_id,
            TaskCompletion.deleted_at.is_(None),
        )
    )
    comp = result.scalar_one_or_none()
    if not comp:
        raise HTTPException(status_code=404, detail="Completion introuvable")
    comp.deleted_at = datetime.now(timezone.utc)
    await record_change(session, "task_completions", comp.id, "DELETE", current["user_id"])
    await session.commit()
    return {"ok": True}


@router.get("/api/completions", response_model=list[CompletionResponse])
async def list_completions(
    user_id: UUID | None = None,
    task_id: UUID | None = None,
    limit: int = 50,
    session: AsyncSession = Depends(get_session),
    _user: dict = Depends(get_current_user),
):
    """Liste les completions recentes avec filtres optionnels."""
    q = select(TaskCompletion).where(TaskCompletion.deleted_at.is_(None))
    if user_id:
        q = q.where(TaskCompletion.completed_by == user_id)
    if task_id:
        q = q.where(TaskCompletion.task_id == task_id)
    q = q.order_by(desc(TaskCompletion.completed_at)).limit(limit)
    result = await session.execute(q)
    return result.scalars().all()
