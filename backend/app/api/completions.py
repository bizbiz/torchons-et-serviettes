"""Routes completions — marquer une tache comme faite."""

from __future__ import annotations

import math
import uuid as uuid_lib
from datetime import datetime, timedelta, timezone
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
from app.models.user_foyer import UserFoyer
from app.schemas.completion import (
    CollectiveCompletionCreate,
    CollectiveCompletionResponse,
    CompletionCreate,
    CompletionResponse,
)
from app.services.recurrence import min_completion_gap_seconds
from app.utils.changelog import record_change
from app.utils.scoping import current_foyer_uuid, scoped

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
    """Marque une tache comme completee (scope foyer)."""
    result = await session.execute(
        scoped(Task, current).where(Task.id == task_id, Task.deleted_at.is_(None))
    )
    task = result.scalar_one_or_none()
    if not task:
        raise HTTPException(status_code=404, detail="Tache introuvable")

    completed_by = body.completed_by or UUID(current["user_id"])

    # Anti-doublon : verifier qu'aucune completion active n'existe dans la fenetre
    # derivee de la recurrence (24h pour hebdo+, 1h sinon).
    gap_seconds = min_completion_gap_seconds(task.recurrence_rule)
    window_start = datetime.now(timezone.utc) - timedelta(seconds=gap_seconds)
    recent = await session.execute(
        select(TaskCompletion)
        .where(
            TaskCompletion.task_id == task_id,
            TaskCompletion.completed_by == completed_by,
            TaskCompletion.deleted_at.is_(None),
            TaskCompletion.completed_at >= window_start,
        )
        .order_by(desc(TaskCompletion.completed_at))
        .limit(1)
    )
    last = recent.scalar_one_or_none()
    if last is not None:
        elapsed = datetime.now(timezone.utc) - last.completed_at
        retry_at = last.completed_at + timedelta(seconds=gap_seconds)
        elapsed_min = int(elapsed.total_seconds() // 60)
        raise HTTPException(
            status_code=409,
            detail={
                "message": (
                    f"Tache deja completee il y a {elapsed_min} min. "
                    f"Prochain creneau possible a {retry_at.isoformat()}."
                ),
                "last_completion_at": last.completed_at.isoformat(),
                "retry_at": retry_at.isoformat(),
                "gap_seconds": gap_seconds,
            },
        )

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

    await record_change(
        session, "task_completions", completion.id, "INSERT", current["user_id"],
        foyer_id=current["foyer_id"],
    )
    await session.commit()
    await session.refresh(completion)

    # Envoyer notification push aux autres utilisateurs assignes
    from app.services.push import send_task_completed_notification
    await send_task_completed_notification(session, current["user_id"], str(task_id), task.title)

    return completion


@router.post(
    "/api/tasks/{task_id}/complete-collective",
    response_model=CollectiveCompletionResponse,
    status_code=201,
)
async def complete_task_collective(
    task_id: UUID,
    body: CollectiveCompletionCreate,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Marque une tache comme completee par N participants (fait ensemble).

    Cree N lignes task_completions partageant le meme collective_id. Points
    divises en ceil integer. Check anti-doublon par participant : si AU MOINS
    un est en conflit, la totalite est abort (atomique) via HTTP 409.
    """
    # 1. Task existe et scope foyer
    result = await session.execute(
        scoped(Task, current).where(Task.id == task_id, Task.deleted_at.is_(None))
    )
    task = result.scalar_one_or_none()
    if not task:
        raise HTTPException(status_code=404, detail="Tache introuvable")

    # 2. Validation : min 1 participant, tous distincts, tous membres du foyer
    participants = list(dict.fromkeys(body.participant_user_ids))  # dedup en preservant ordre
    if not participants:
        raise HTTPException(status_code=400, detail="Aucun participant fourni")

    foyer_uuid = current_foyer_uuid(current)
    members_result = await session.execute(
        select(UserFoyer.user_id).where(
            UserFoyer.foyer_id == foyer_uuid,
            UserFoyer.user_id.in_(participants),
        )
    )
    valid_ids = {row[0] for row in members_result.all()}
    invalid = [p for p in participants if p not in valid_ids]
    if invalid:
        raise HTTPException(
            status_code=400,
            detail=f"Participants hors du foyer : {[str(i) for i in invalid]}",
        )

    # 3. Anti-doublon atomique : pour chaque participant, verifier qu'aucune
    # completion active n'existe dans la fenetre derivee de la recurrence.
    gap_seconds = min_completion_gap_seconds(task.recurrence_rule)
    window_start = datetime.now(timezone.utc) - timedelta(seconds=gap_seconds)
    conflict_result = await session.execute(
        select(TaskCompletion)
        .where(
            TaskCompletion.task_id == task_id,
            TaskCompletion.completed_by.in_(participants),
            TaskCompletion.deleted_at.is_(None),
            TaskCompletion.completed_at >= window_start,
        )
    )
    conflicts = conflict_result.scalars().all()
    if conflicts:
        now = datetime.now(timezone.utc)
        details = []
        for c in conflicts:
            elapsed_min = int((now - c.completed_at).total_seconds() // 60)
            retry_at = c.completed_at + timedelta(seconds=gap_seconds)
            details.append({
                "user_id": str(c.completed_by),
                "elapsed_min": elapsed_min,
                "retry_at": retry_at.isoformat(),
            })
        raise HTTPException(
            status_code=409,
            detail={
                "message": (
                    "Impossible de valider : un ou plusieurs participants ont "
                    "deja valide cette tache recemment."
                ),
                "conflicts": details,
                "gap_seconds": gap_seconds,
            },
        )

    # 4. Base points : meme logique que le endpoint classique.
    assign_result = await session.execute(
        select(func.count(TaskAssignment.user_id)).where(TaskAssignment.task_id == task_id)
    )
    num_assigned = assign_result.scalar() or 1
    if num_assigned <= 1:
        base_points = 0
    else:
        avg_result = await session.execute(
            select(func.avg(TaskEffort.points)).where(TaskEffort.task_id == task_id)
        )
        avg_effort = avg_result.scalar()
        base_points = round(avg_effort) if avg_effort else task.weight

    # Split ceil integer entre les participants. Si N=1, reste a base_points.
    points_per_user = math.ceil(base_points / len(participants)) if base_points else 0

    # 5. Creation des N lignes avec un meme collective_id.
    collective_id = uuid_lib.uuid4()
    completed_at = body.completed_at or datetime.now(timezone.utc)
    device_id = _safe_uuid(current.get("device_id"))

    created: list[TaskCompletion] = []
    for user_id in participants:
        comp = TaskCompletion(
            task_id=task_id,
            completed_by=user_id,
            points_earned=points_per_user,
            notes=body.notes,
            client_id=device_id,
            collective_id=collective_id,
        )
        comp.completed_at = completed_at
        session.add(comp)
        created.append(comp)
    await session.flush()

    # 6. Supprimer la tache "en cours" si elle existe (meme si la UI "en cours"
    # a ete retiree en v0.11, des entrees legacy peuvent subsister cote DB).
    from sqlalchemy import delete
    await session.execute(
        delete(TaskInProgress).where(TaskInProgress.task_id == task_id)
    )

    # 7. Auto-desactiver les taches ponctuelles (une seule fois).
    if task.recurrence_rule is None:
        task.is_active = False
        task.version += 1

    # 8. Changelog : une entree par ligne pour que la sync offline rapatrie tout.
    for comp in created:
        await record_change(
            session, "task_completions", comp.id, "INSERT", current["user_id"],
            foyer_id=current["foyer_id"],
        )
    await session.commit()
    for comp in created:
        await session.refresh(comp)

    # 9. Notification push (une seule pour l'evenement collectif).
    from app.services.push import send_task_completed_notification
    await send_task_completed_notification(
        session, current["user_id"], str(task_id), task.title
    )

    return CollectiveCompletionResponse(
        collective_id=collective_id,
        completions=[CompletionResponse.model_validate(c) for c in created],
    )


@router.delete("/api/tasks/{task_id}/completions/{completion_id}")
async def undo_completion(
    task_id: UUID,
    completion_id: UUID,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Annule une completion (soft delete). Scope foyer via JOIN tasks."""
    # Verifie que la completion existe ET appartient au foyer courant via Task
    result = await session.execute(
        select(TaskCompletion)
        .join(Task, Task.id == TaskCompletion.task_id)
        .where(
            TaskCompletion.id == completion_id,
            TaskCompletion.task_id == task_id,
            TaskCompletion.deleted_at.is_(None),
            Task.foyer_id == current_foyer_uuid(current),
        )
    )
    comp = result.scalar_one_or_none()
    if not comp:
        raise HTTPException(status_code=404, detail="Completion introuvable")
    comp.deleted_at = datetime.now(timezone.utc)
    await record_change(
        session, "task_completions", comp.id, "DELETE", current["user_id"],
        foyer_id=current["foyer_id"],
    )
    await session.commit()
    return {"ok": True}


@router.get("/api/completions", response_model=list[CompletionResponse])
async def list_completions(
    user_id: UUID | None = None,
    task_id: UUID | None = None,
    limit: int = 50,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Liste les completions recentes avec filtres optionnels. Scope foyer via JOIN tasks."""
    q = (
        select(TaskCompletion)
        .join(Task, Task.id == TaskCompletion.task_id)
        .where(
            TaskCompletion.deleted_at.is_(None),
            Task.foyer_id == current_foyer_uuid(current),
        )
    )
    if user_id:
        q = q.where(TaskCompletion.completed_by == user_id)
    if task_id:
        q = q.where(TaskCompletion.task_id == task_id)
    q = q.order_by(desc(TaskCompletion.completed_at)).limit(limit)
    result = await session.execute(q)
    return result.scalars().all()
