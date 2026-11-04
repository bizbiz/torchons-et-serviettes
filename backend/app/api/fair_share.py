"""Fair-share routes: status, target shares of the household, and their history.

The status and config routes used to live in gamification.py (same URLs).
"""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel, Field
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.services.fair_share import apply_targets, compute_status, load_history
from app.utils.scoping import current_foyer_uuid

router = APIRouter(prefix="/api", tags=["fair-share"])


class FairShareStatus(BaseModel):
    user_id: UUID
    display_name: str
    target_ratio: float
    actual_ratio: float
    above_target: bool


class FairShareConfigUpdate(BaseModel):
    """Target share for one member (ex: 0.6 = 60%)."""
    user_id: UUID
    target_ratio: float = Field(ge=0.0, le=1.0)


class FairShareHistoryShare(BaseModel):
    user_id: UUID
    display_name: str
    before: float | None
    after: float | None


class FairShareHistoryEntry(BaseModel):
    id: UUID
    changed_at: datetime
    changed_by: UUID
    changed_by_name: str
    shares: list[FairShareHistoryShare]


@router.get("/fair-share/status", response_model=list[FairShareStatus])
async def fair_share_status(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Target vs actual share over the last 30 days, for the active members of the
    active household. A member without a stored target has the equal share, 1/N.
    """
    return await compute_status(session, current_foyer_uuid(current))


@router.put("/fair-share/config")
async def update_fair_share(
    body: list[FairShareConfigUpdate],
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Set the targets of the active household. One entry per active member, total 1 +/- 0.01
    (else 422). Each accepted call adds a line to the history.
    """
    await apply_targets(
        session,
        current_foyer_uuid(current),
        UUID(current["user_id"]),
        [(item.user_id, item.target_ratio) for item in body],
    )
    await session.commit()
    return {"ok": True}


@router.get("/fair-share/history", response_model=list[FairShareHistoryEntry])
async def fair_share_history(
    limit: int = Query(100, ge=1, le=500),
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Who changed the target shares of the active household, when, and the shares before
    and after. Most recent first.
    """
    return await load_history(session, current_foyer_uuid(current), limit)
