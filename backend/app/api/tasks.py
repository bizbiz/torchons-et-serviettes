"""Routes CRUD taches."""

from __future__ import annotations

from datetime import datetime, timezone
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select, delete
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.models.sync import ChangeLog
from app.models.task import Task
from app.models.task_assignment import TaskAssignment
from app.models.user import User
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
        "room_id": task.room_id,
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
        room_id=body.room_id,
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

    # Snapshot de l'etat avant mutation (uniquement pour les champs effectivement
    # modifies). Permet a l'historique d'afficher "ancien -> nouveau".
    old_assigned_ids = await _get_assigned_ids(session, task_id)
    old_data: dict = {}
    for key in updates.keys():
        if key == "assigned_to_ids":
            old_data["assigned_to_ids"] = [str(u) for u in old_assigned_ids]
        else:
            val = getattr(task, key, None)
            if hasattr(val, "value"):  # enum (tolerance)
                val = val.value
            old_data[key] = val

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
        old_data=old_data,
        new_data=body.model_dump(mode="json", exclude_unset=True),
    )
    await session.commit()
    await session.refresh(task)
    return await _task_to_response(session, task)


_HISTORY_TABLES = (
    "tasks",
    "task_affinities",
    "task_efforts",
    "task_frequency_votes",
)


def _event_type(table_name: str, operation: str) -> str:
    """Mappe une ligne change_log vers un type d'evenement d'historique."""
    if table_name == "task_affinities":
        return "vote_affinity"
    if table_name == "task_efforts":
        return "vote_effort"
    if table_name == "task_frequency_votes":
        return "vote_frequency"
    return "task_update"


def _fields_changed(old_data: dict | None, new_data: dict | None) -> list[str]:
    """Liste les champs modifies dans un UPDATE tasks (hors version/updated_at)."""
    if not new_data:
        return []
    ignored = {"version", "updated_at", "created_at", "id"}
    return [k for k in new_data.keys() if k not in ignored]


@router.get("/{task_id}/history")
async def get_task_history(
    task_id: UUID,
    limit: int = 100,
    session: AsyncSession = Depends(get_session),
    _user: dict = Depends(get_current_user),
):
    """Retourne l'historique unifie d'une tache : votes + modifs directes.
    Lit change_log filtre par (table_name, row_id=task_id), jointe avec users
    pour le nom de l'auteur. Dedoublonne les lignes tasks/UPDATE emises en
    meme temps qu'un vote de frequence (le vote genere les deux entrees).
    """
    result = await session.execute(
        select(
            ChangeLog.id,
            ChangeLog.table_name,
            ChangeLog.operation,
            ChangeLog.changed_by,
            ChangeLog.changed_at,
            ChangeLog.old_data,
            ChangeLog.new_data,
            User.display_name,
        )
        .outerjoin(User, User.id == ChangeLog.changed_by)
        .where(
            ChangeLog.row_id == task_id,
            ChangeLog.table_name.in_(_HISTORY_TABLES),
        )
        .order_by(ChangeLog.changed_at.desc())
        .limit(limit)
    )
    rows = result.all()

    # Dedoublonnage : quand un vote de frequence genere un tasks/UPDATE avec
    # new_data = {"recurrence_rule": ...} et un task_frequency_votes/VOTE
    # du meme user a ±2s, on masque le tasks/UPDATE (doublon pour l'UI).
    freq_votes_ts: list[tuple[datetime, UUID | None]] = [
        (r.changed_at, r.changed_by)
        for r in rows
        if r.table_name == "task_frequency_votes"
    ]

    events = []
    for r in rows:
        if (
            r.table_name == "tasks"
            and r.operation == "UPDATE"
            and r.new_data
            and set(r.new_data.keys()) == {"recurrence_rule"}
        ):
            skip = any(
                r.changed_by == fv[1]
                and abs((r.changed_at - fv[0]).total_seconds()) <= 2
                for fv in freq_votes_ts
            )
            if skip:
                continue

        evt_type = _event_type(r.table_name, r.operation)
        new_data = r.new_data or {}
        old_data = r.old_data or {}

        details: dict = {
            "operation": r.operation,
            "old": old_data,
            "new": new_data,
        }
        if evt_type == "vote_affinity":
            details.update(
                value=new_data.get("score"),
                consensus_before=new_data.get("consensus_before"),
                consensus_after=new_data.get("consensus_after"),
            )
        elif evt_type == "vote_effort":
            details.update(
                value=new_data.get("points"),
                consensus_before=new_data.get("consensus_before"),
                consensus_after=new_data.get("consensus_after"),
            )
        elif evt_type == "vote_frequency":
            details.update(
                value=new_data.get("every_n_days"),
                consensus_before=new_data.get("consensus_before"),
                consensus_after=new_data.get("consensus_after"),
            )
        else:
            details["fields_changed"] = _fields_changed(old_data, new_data)

        events.append({
            "id": r.id,
            "event_type": evt_type,
            "actor_user_id": str(r.changed_by) if r.changed_by else None,
            "actor_name": r.display_name or "",
            "at": r.changed_at.isoformat() if r.changed_at else None,
            "details": details,
        })

    return events


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
