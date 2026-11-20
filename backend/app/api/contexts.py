"""CRUD routes for contexts — the single concept replacing rooms and categories.

Contexts are the only model: rooms and categories no longer exist.

**Detaching on delete.** A soft-deleted context loses its `task_contexts`
links. Keeping them would leave tasks pointing at something no filter lists,
which is exactly how a task becomes invisible without being deleted.
"""

from __future__ import annotations

from datetime import datetime, timezone
from uuid import UUID

from fastapi import APIRouter, Depends
from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.models.context import Context, TaskContext
from app.schemas.context import ContextCreate, ContextResponse, ContextUpdate
from app.utils.changelog import record_change
from app.utils.errors import LocalizedHTTPException
from app.utils.row_lock import lock_key
from app.utils.scoping import current_foyer_uuid, scoped

router = APIRouter(prefix="/api/contexts", tags=["contexts"])


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
    # Two copies of one create (the sync worker resends) must give the 409 below, not a 500
    # on uq_contexts_name_foyer.
    await lock_key(session, f"context-name:{foyer_id}:{body.name}")
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

    await record_change(
        session, "contexts", ctx.id, "DELETE", current["user_id"],
        foyer_id=current["foyer_id"],
    )
    await session.commit()
    return {"ok": True, "orphaned_tasks": orphelines}
