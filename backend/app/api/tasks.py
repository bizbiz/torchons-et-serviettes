"""Routes CRUD taches."""

from __future__ import annotations

from datetime import datetime, timezone
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select, delete
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.models.task import Task
from app.models.task_assignment import TaskAssignment
from app.schemas.task import TaskCreate, TaskResponse, TaskUpdate
from app.utils.changelog import record_change

router = APIRouter(prefix="/api/tasks", tags=["tasks"])


async def _get_assigned_ids(session: AsyncSession, task_id: UUID) -> list[UUID]:
    """Recupere les IDs des utilisateurs assignes a une tache."""
    result = await session.execute(
        select(TaskAssignment.user_id).where(TaskAssignment.task_id == task_id)
    )
    return [row[0] for row in result.all()]


async def _task_to_response(session: AsyncSession, task: Task) -> dict:
    """Construit la reponse d'une tache avec ses assignments."""
    assigned_ids = await _get_assigned_ids(session, task.id)
    return {
        "id": task.id,
        "title": task.title,
        "description": task.description,
        "category_id": task.category_id,
        "assigned_to_ids": assigned_ids,
        "recurrence_rule": task.recurrence_rule,
        "weight": task.weight,
        "tolerance": task.tolerance,
        "is_active": task.is_active,
        "sort_order": task.sort_order,
        "created_at": task.created_at,
        "updated_at": task.updated_at,
    }


@router.get("", response_model=list[TaskResponse])
async def list_tasks(
    category_id: UUID | None = None,
    active_only: bool = True,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Liste des taches visibles par l'utilisateur courant."""
    user_id = UUID(current["user_id"])

    # Sous-requete : taches assignees a l'utilisateur courant
    assigned_task_ids = select(TaskAssignment.task_id).where(
        TaskAssignment.user_id == user_id
    ).scalar_subquery()

    q = select(Task).where(
        Task.deleted_at.is_(None),
        Task.id.in_(assigned_task_ids),
    )
    if active_only:
        q = q.where(Task.is_active.is_(True))
    if category_id:
        q = q.where(Task.category_id == category_id)
    q = q.order_by(Task.sort_order, Task.created_at)

    result = await session.execute(q)
    tasks = result.scalars().all()

    return [await _task_to_response(session, t) for t in tasks]


@router.get("/{task_id}", response_model=TaskResponse)
async def get_task(
    task_id: UUID,
    session: AsyncSession = Depends(get_session),
    _user: dict = Depends(get_current_user),
):
    result = await session.execute(
        select(Task).where(Task.id == task_id, Task.deleted_at.is_(None))
    )
    task = result.scalar_one_or_none()
    if not task:
        raise HTTPException(status_code=404, detail="Tache introuvable")
    return await _task_to_response(session, task)


@router.post("", response_model=TaskResponse, status_code=201)
async def create_task(
    body: TaskCreate,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    task = Task(
        title=body.title,
        description=body.description,
        category_id=body.category_id,
        recurrence_rule=body.recurrence_rule.model_dump() if body.recurrence_rule else None,
        weight=body.weight,
        tolerance=body.tolerance,
    )
    session.add(task)
    await session.flush()

    # Creer les assignments
    for uid in body.assigned_to_ids:
        session.add(TaskAssignment(task_id=task.id, user_id=uid))
    await session.flush()

    await record_change(
        session, "tasks", task.id, "INSERT", current["user_id"],
        new_data=body.model_dump(mode="json"),
    )
    await session.commit()
    await session.refresh(task)
    return await _task_to_response(session, task)


@router.put("/{task_id}", response_model=TaskResponse)
async def update_task(
    task_id: UUID,
    body: TaskUpdate,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    result = await session.execute(
        select(Task).where(Task.id == task_id, Task.deleted_at.is_(None))
    )
    task = result.scalar_one_or_none()
    if not task:
        raise HTTPException(status_code=404, detail="Tache introuvable")

    updates = body.model_dump(exclude_unset=True)

    # Gerer les assignments separement
    new_assigned_ids = updates.pop("assigned_to_ids", None)
    if new_assigned_ids is not None:
        await session.execute(
            delete(TaskAssignment).where(TaskAssignment.task_id == task_id)
        )
        for uid in new_assigned_ids:
            session.add(TaskAssignment(task_id=task_id, user_id=uid))

    # Mettre a jour les champs de la tache
    if "recurrence_rule" in updates and updates["recurrence_rule"]:
        rr = updates["recurrence_rule"]
        updates["recurrence_rule"] = rr.model_dump() if hasattr(rr, "model_dump") else rr
    for key, val in updates.items():
        setattr(task, key, val)
    task.version += 1

    await record_change(
        session, "tasks", task.id, "UPDATE", current["user_id"],
        new_data=body.model_dump(mode="json", exclude_unset=True),
    )
    await session.commit()
    await session.refresh(task)
    return await _task_to_response(session, task)


@router.delete("/{task_id}")
async def delete_task(
    task_id: UUID,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Soft delete."""
    result = await session.execute(
        select(Task).where(Task.id == task_id, Task.deleted_at.is_(None))
    )
    task = result.scalar_one_or_none()
    if not task:
        raise HTTPException(status_code=404, detail="Tache introuvable")
    task.deleted_at = datetime.now(timezone.utc)
    task.version += 1
    await record_change(session, "tasks", task.id, "DELETE", current["user_id"])
    await session.commit()
    return {"ok": True}
