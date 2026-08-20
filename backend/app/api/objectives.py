"""Endpoints CRUD objectifs personnels et partages (v0.31).

Roadmap user-facing : "Objectifs personnels et partages (ex : 'nettoyer
la cuisine 3x par semaine')". Voir backend/app/models/objective.py pour
le modele de donnees.

4 endpoints :
- GET    /api/objectives              : liste avec progress calcule
- POST   /api/objectives              : creer
- PUT    /api/objectives/{id}         : modifier (partial)
- DELETE /api/objectives/{id}         : soft-delete

Le progress est calcule dynamiquement : pour chaque objectif actif, on
compte les TaskCompletion sur la fenetre temporelle (day/week/month) +
scope (room/category) + user (si kind=personal). Pas de cache — les
volumes sont faibles, recalcul a chaque GET acceptable.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone
from uuid import UUID

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field
from sqlalchemy import and_, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.utils.errors import LocalizedHTTPException
from app.models.completion import TaskCompletion
from app.models.objective import Objective
from app.models.context import TaskContext
from app.models.task import Task
from app.utils.changelog import record_change
from app.utils.scoping import current_foyer_uuid

router = APIRouter(prefix="/api/objectives", tags=["objectives"])


# --- Schemas ---

class ObjectiveCreate(BaseModel):
    title: str = Field(min_length=1, max_length=120)
    kind: str = Field(default="shared", pattern="^(personal|shared)$")
    period: str = Field(default="week", pattern="^(day|week|month)$")
    target_count: int = Field(default=1, ge=1, le=999)
    scope_context_id: UUID | None = None
    assigned_to_user_id: UUID | None = None
    is_active: bool = True


class ObjectiveUpdate(BaseModel):
    title: str | None = Field(default=None, max_length=120)
    kind: str | None = Field(default=None, pattern="^(personal|shared)$")
    period: str | None = Field(default=None, pattern="^(day|week|month)$")
    target_count: int | None = Field(default=None, ge=1, le=999)
    scope_context_id: UUID | None = None
    assigned_to_user_id: UUID | None = None
    is_active: bool | None = None


class ObjectiveResponse(BaseModel):
    id: UUID
    title: str
    kind: str
    period: str
    target_count: int
    scope_context_id: UUID | None
    assigned_to_user_id: UUID | None
    is_active: bool
    progress_count: int  # nb de completions matching sur la periode courante
    progress_percent: int  # 0..100, capped
    created_at: datetime


# --- Helpers ---


def _period_window_start(period: str) -> datetime:
    """Debut de la fenetre courante pour `period`. UTC, ISO 8601 week =
    lundi pour 'week'."""
    now = datetime.now(timezone.utc)
    if period == "day":
        return datetime.combine(now.date(), datetime.min.time(), tzinfo=timezone.utc)
    if period == "week":
        monday = now.date() - timedelta(days=now.weekday())
        return datetime.combine(monday, datetime.min.time(), tzinfo=timezone.utc)
    if period == "month":
        first = now.date().replace(day=1)
        return datetime.combine(first, datetime.min.time(), tzinfo=timezone.utc)
    # Fallback inattendu
    return now - timedelta(days=7)


async def _compute_progress(
    session: AsyncSession, obj: Objective, foyer_uuid: uuid.UUID,
) -> int:
    """Compte les TaskCompletion matching pour cet objectif sur la fenetre
    courante. Filtres applique :
      - foyer = foyer_uuid (via Task.foyer_id)
      - completed_at >= window_start
      - completed_by = obj.assigned_to_user_id (si kind=personal)
      - la tache porte le contexte obj.scope_context_id (si non null)
    """
    window_start = _period_window_start(obj.period)
    stmt = (
        select(func.count(TaskCompletion.id))
        .join(Task, Task.id == TaskCompletion.task_id)
        .where(
            Task.foyer_id == foyer_uuid,
            TaskCompletion.deleted_at.is_(None),
            TaskCompletion.completed_at >= window_start,
        )
    )
    if obj.kind == "personal" and obj.assigned_to_user_id is not None:
        stmt = stmt.where(TaskCompletion.completed_by == obj.assigned_to_user_id)
    if obj.scope_context_id is not None:
        # `EXISTS` et non une jointure : une tache porte plusieurs contextes,
        # et joindre multiplierait ses completions par ce nombre.
        stmt = stmt.where(
            select(TaskContext.task_id)
            .where(
                TaskContext.task_id == Task.id,
                TaskContext.context_id == obj.scope_context_id,
            )
            .exists()
        )
    result = await session.execute(stmt)
    return int(result.scalar_one() or 0)


def _to_response(obj: Objective, progress: int) -> ObjectiveResponse:
    target = max(obj.target_count, 1)
    pct = min(100, int(progress * 100 / target))
    return ObjectiveResponse(
        id=obj.id,
        title=obj.title,
        kind=obj.kind,
        period=obj.period,
        target_count=obj.target_count,
        scope_room_id=obj.scope_room_id,
        scope_category_id=obj.scope_category_id,
        assigned_to_user_id=obj.assigned_to_user_id,
        is_active=obj.is_active,
        progress_count=progress,
        progress_percent=pct,
        created_at=obj.created_at,
    )


# --- Endpoints ---


@router.get("", response_model=list[ObjectiveResponse])
async def list_objectives(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Liste les objectifs actifs du foyer + progress calcule par objectif."""
    foyer_uuid = current_foyer_uuid(current)
    result = await session.execute(
        select(Objective).where(
            Objective.foyer_id == foyer_uuid,
            Objective.deleted_at.is_(None),
            Objective.is_active.is_(True),
        ).order_by(Objective.created_at.desc())
    )
    objectives = list(result.scalars().all())
    out: list[ObjectiveResponse] = []
    for obj in objectives:
        progress = await _compute_progress(session, obj, foyer_uuid)
        out.append(_to_response(obj, progress))
    return out


@router.post("", response_model=ObjectiveResponse, status_code=201)
async def create_objective(
    body: ObjectiveCreate,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Cree un objectif. Si kind=personal, assigned_to_user_id est requis."""
    foyer_uuid = current_foyer_uuid(current)
    if body.kind == "personal" and body.assigned_to_user_id is None:
        raise LocalizedHTTPException(
            status_code=400,
            error_code="OBJECTIVE_PERSONAL_REQUIRES_USER",
        )

    obj = Objective(
        foyer_id=foyer_uuid,
        title=body.title.strip(),
        kind=body.kind,
        period=body.period,
        target_count=body.target_count,
        scope_room_id=body.scope_room_id,
        scope_category_id=body.scope_category_id,
        assigned_to_user_id=body.assigned_to_user_id,
        is_active=body.is_active,
    )
    session.add(obj)
    await session.flush()
    await record_change(
        session, "objectives", obj.id, "INSERT", current["user_id"],
        foyer_id=current["foyer_id"],
        new_data={"title": obj.title, "period": obj.period},
    )
    await session.commit()
    await session.refresh(obj)

    progress = await _compute_progress(session, obj, foyer_uuid)
    return _to_response(obj, progress)


@router.put("/{objective_id}", response_model=ObjectiveResponse)
async def update_objective(
    objective_id: UUID,
    body: ObjectiveUpdate,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Modifie un objectif (partial update, fields fournis seulement)."""
    foyer_uuid = current_foyer_uuid(current)
    result = await session.execute(
        select(Objective).where(
            Objective.id == objective_id,
            Objective.foyer_id == foyer_uuid,
            Objective.deleted_at.is_(None),
        )
    )
    obj = result.scalar_one_or_none()
    if obj is None:
        raise LocalizedHTTPException(status_code=404, error_code="OBJECTIVE_NOT_FOUND")

    updates = body.model_dump(exclude_unset=True)
    if updates.get("kind") == "personal" and updates.get("assigned_to_user_id") is None and obj.assigned_to_user_id is None:
        raise LocalizedHTTPException(
            status_code=400,
            error_code="OBJECTIVE_PERSONAL_REQUIRES_USER",
        )
    for key, val in updates.items():
        setattr(obj, key, val)
    obj.version += 1
    await record_change(
        session, "objectives", obj.id, "UPDATE", current["user_id"],
        foyer_id=current["foyer_id"],
        new_data={k: str(v) for k, v in updates.items()},
    )
    await session.commit()
    await session.refresh(obj)

    progress = await _compute_progress(session, obj, foyer_uuid)
    return _to_response(obj, progress)


@router.delete("/{objective_id}", status_code=204)
async def delete_objective(
    objective_id: UUID,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Soft-delete un objectif. La row reste pour audit."""
    foyer_uuid = current_foyer_uuid(current)
    result = await session.execute(
        select(Objective).where(
            Objective.id == objective_id,
            Objective.foyer_id == foyer_uuid,
            Objective.deleted_at.is_(None),
        )
    )
    obj = result.scalar_one_or_none()
    if obj is None:
        raise LocalizedHTTPException(status_code=404, error_code="OBJECTIVE_NOT_FOUND")

    obj.deleted_at = datetime.now(timezone.utc)
    obj.is_active = False
    obj.version += 1
    await record_change(
        session, "objectives", obj.id, "DELETE", current["user_id"],
        foyer_id=current["foyer_id"],
    )
    await session.commit()
