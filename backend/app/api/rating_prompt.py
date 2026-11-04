"""Outcome of the automatic rating pop-up (see app/services/rating_cadence.py)."""

from __future__ import annotations

from datetime import datetime, timezone
from uuid import UUID

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.services.rating_cadence import apply_outcome
from app.utils.user_settings import get_or_create_user_settings

router = APIRouter(prefix="/api/rating-prompt", tags=["settings"])


class RatingPromptOutcome(BaseModel):
    answered: bool


class RatingPromptState(BaseModel):
    gap_days: int
    next_at: datetime


@router.post("/outcome", response_model=RatingPromptState)
async def report_outcome(
    body: RatingPromptOutcome,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
) -> RatingPromptState:
    """Apply one automatic pop-up outcome: answered narrows the gap, dismissed widens it."""
    settings = await get_or_create_user_settings(session, UUID(current["user_id"]))
    # Lock the row: two devices reporting together must not both read the same gap.
    await session.refresh(settings, with_for_update=True)
    apply_outcome(settings, body.answered, datetime.now(timezone.utc))
    state = RatingPromptState(
        gap_days=settings.rating_prompt_gap_days, next_at=settings.rating_prompt_next_at
    )
    await session.commit()
    return state
