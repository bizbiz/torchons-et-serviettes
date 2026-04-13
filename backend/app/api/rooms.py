"""Routes CRUD pieces (rooms). Mirror du pattern categories.py."""

from __future__ import annotations

from datetime import datetime, timezone
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.models.room import Room
from app.models.task import Task
from app.schemas.room import RoomCreate, RoomResponse, RoomUpdate
from app.utils.changelog import record_change

router = APIRouter(prefix="/api/rooms", tags=["rooms"])


@router.get("", response_model=list[RoomResponse])
async def list_rooms(
    session: AsyncSession = Depends(get_session),
    _user: dict = Depends(get_current_user),
):
    """Liste les pieces non supprimees, triees alphabetiquement."""
    result = await session.execute(
        select(Room).where(Room.deleted_at.is_(None)).order_by(Room.name)
    )
    return result.scalars().all()


@router.post("", response_model=RoomResponse, status_code=201)
async def create_room(
    body: RoomCreate,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Cree une nouvelle piece. Le nom doit etre unique."""
    room = Room(
        name=body.name,
        color=body.color,
        icon=body.icon,
        emoji=body.emoji,
    )
    session.add(room)
    await session.flush()
    await record_change(session, "rooms", room.id, "INSERT", current["user_id"])
    await session.commit()
    await session.refresh(room)
    return room


@router.put("/{room_id}", response_model=RoomResponse)
async def update_room(
    room_id: UUID,
    body: RoomUpdate,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Met a jour une piece existante."""
    result = await session.execute(
        select(Room).where(Room.id == room_id, Room.deleted_at.is_(None))
    )
    room = result.scalar_one_or_none()
    if not room:
        raise HTTPException(status_code=404, detail="Piece introuvable")
    for key, val in body.model_dump(exclude_unset=True).items():
        setattr(room, key, val)
    room.version += 1
    await record_change(session, "rooms", room.id, "UPDATE", current["user_id"])
    await session.commit()
    await session.refresh(room)
    return room


@router.delete("/{room_id}")
async def delete_room(
    room_id: UUID,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Soft delete d'une piece. Les taches liees passent en room_id=NULL.
    Mirror du comportement de delete_category.
    """
    result = await session.execute(
        select(Room).where(Room.id == room_id, Room.deleted_at.is_(None))
    )
    room = result.scalar_one_or_none()
    if not room:
        raise HTTPException(status_code=404, detail="Piece introuvable")

    # Detache les taches de cette piece
    result_tasks = await session.execute(
        update(Task).where(Task.room_id == room_id).values(room_id=None).returning(Task.id)
    )
    orphaned_count = len(result_tasks.all())

    room.deleted_at = datetime.now(timezone.utc)
    room.version += 1
    await record_change(session, "rooms", room.id, "DELETE", current["user_id"])
    await session.commit()
    return {"ok": True, "orphaned_tasks": orphaned_count}
