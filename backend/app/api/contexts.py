"""CRUD routes for contexts — the single concept replacing rooms and categories.

Mirrors the `rooms.py` pattern, with two differences that matter.

**Dual write.** Creating a context also creates its twin `Room`, and renaming
or deleting one carries over. The shipped APK reads `rooms`; without this, a
context created on the new client would simply not exist for a household
member still on the old one, and their tasks would show no location at all.
The twin is matched by name, which is what the fusion migration used too.
Removal is tracked in `todo.md § Retirer la double écriture`.

**Detaching on delete.** A soft-deleted context loses its `task_contexts`
links. Keeping them would leave tasks pointing at something no filter lists,
which is exactly how a task becomes invisible without being deleted.
"""

from __future__ import annotations

from datetime import datetime, timezone
from uuid import UUID

from fastapi import APIRouter, Depends
from sqlalchemy import delete, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.models.context import Context, TaskContext
from app.models.room import Room
from app.models.task import Task
from app.schemas.context import ContextCreate, ContextResponse, ContextUpdate
from app.utils.changelog import record_change
from app.utils.errors import LocalizedHTTPException
from app.utils.scoping import current_foyer_uuid, scoped

router = APIRouter(prefix="/api/contexts", tags=["contexts"])


async def _twin_room(session: AsyncSession, foyer_id: UUID, name: str) -> Room | None:
    """The legacy room bearing this name, if any. Compatibility shim."""
    result = await session.execute(
        select(Room).where(
            Room.foyer_id == foyer_id,
            Room.deleted_at.is_(None),
            Room.name == name,
        )
    )
    return result.scalar_one_or_none()


@router.get("", response_model=list[ContextResponse])
async def list_contexts(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Live contexts of the household, in display order then name."""
    result = await session.execute(
        scoped(Context, current)
        .where(Context.deleted_at.is_(None))
        .order_by(Context.sort_order, Context.name)
    )
    return result.scalars().all()


@router.post("", response_model=ContextResponse, status_code=201)
async def create_context(
    body: ContextCreate,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    foyer_id = current_foyer_uuid(current)
    existing = await session.execute(
        scoped(Context, current).where(
            Context.deleted_at.is_(None), Context.name == body.name
        )
    )
    if existing.scalar_one_or_none() is not None:
        raise LocalizedHTTPException(status_code=409, error_code="CONTEXT_NAME_TAKEN")

    ctx = Context(
        foyer_id=foyer_id,
        name=body.name,
        color=body.color,
        icon=body.icon,
        emoji=body.emoji,
        requires_presence=body.requires_presence,
        accrues_when_away=body.accrues_when_away,
        sort_order=body.sort_order,
    )
    session.add(ctx)
    await session.flush()

    if await _twin_room(session, foyer_id, body.name) is None:
        session.add(Room(
            foyer_id=foyer_id, name=body.name, color=body.color,
            icon=body.icon, emoji=body.emoji,
        ))

    await record_change(
        session, "contexts", ctx.id, "INSERT", current["user_id"],
        foyer_id=current["foyer_id"],
    )
    await session.commit()
    await session.refresh(ctx)
    return ctx


@router.put("/{context_id}", response_model=ContextResponse)
async def update_context(
    context_id: UUID,
    body: ContextUpdate,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    foyer_id = current_foyer_uuid(current)
    result = await session.execute(
        scoped(Context, current).where(
            Context.id == context_id, Context.deleted_at.is_(None)
        )
    )
    ctx = result.scalar_one_or_none()
    if not ctx:
        raise LocalizedHTTPException(status_code=404, error_code="CONTEXT_NOT_FOUND")

    champs = body.model_dump(exclude_unset=True)
    ancien_nom = ctx.name
    if "name" in champs and champs["name"] != ancien_nom:
        collision = await session.execute(
            scoped(Context, current).where(
                Context.deleted_at.is_(None),
                Context.name == champs["name"],
                Context.id != context_id,
            )
        )
        if collision.scalar_one_or_none() is not None:
            raise LocalizedHTTPException(
                status_code=409, error_code="CONTEXT_NAME_TAKEN"
            )

    for key, val in champs.items():
        setattr(ctx, key, val)
    ctx.version += 1

    # Le jumeau herite du renommage, sinon l'ancien client garde l'ancien nom.
    jumeau = await _twin_room(session, foyer_id, ancien_nom)
    if jumeau is not None:
        for key in ("name", "color", "icon", "emoji"):
            if key in champs:
                setattr(jumeau, key, champs[key])
        jumeau.version += 1

    await record_change(
        session, "contexts", ctx.id, "UPDATE", current["user_id"],
        foyer_id=current["foyer_id"],
    )
    await session.commit()
    await session.refresh(ctx)
    return ctx


@router.delete("/{context_id}")
async def delete_context(
    context_id: UUID,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Soft delete. Links are dropped, and tasks left with none are reported."""
    foyer_id = current_foyer_uuid(current)
    result = await session.execute(
        scoped(Context, current).where(
            Context.id == context_id, Context.deleted_at.is_(None)
        )
    )
    ctx = result.scalar_one_or_none()
    if not ctx:
        raise LocalizedHTTPException(status_code=404, error_code="CONTEXT_NOT_FOUND")

    touchees = (await session.execute(
        select(TaskContext.task_id).where(TaskContext.context_id == context_id)
    )).scalars().all()
    await session.execute(
        delete(TaskContext).where(TaskContext.context_id == context_id)
    )

    # Combien de taches se retrouvent SANS aucun contexte : c'est le chiffre
    # qui compte pour l'utilisateur, pas le nombre de liens coupes.
    orphelines = 0
    if touchees:
        restants = (await session.execute(
            select(TaskContext.task_id).where(TaskContext.task_id.in_(touchees))
        )).scalars().all()
        orphelines = len(set(touchees) - set(restants))

    ctx.deleted_at = datetime.now(timezone.utc)
    ctx.version += 1

    jumeau = await _twin_room(session, foyer_id, ctx.name)
    if jumeau is not None:
        await session.execute(
            update(Task)
            .where(Task.room_id == jumeau.id, Task.foyer_id == foyer_id)
            .values(room_id=None)
        )
        jumeau.deleted_at = datetime.now(timezone.utc)
        jumeau.version += 1

    await record_change(
        session, "contexts", ctx.id, "DELETE", current["user_id"],
        foyer_id=current["foyer_id"],
    )
    await session.commit()
    return {"ok": True, "orphaned_tasks": orphelines}
