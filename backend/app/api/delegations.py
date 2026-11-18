"""GET /api/delegations — the « À faire aujourd'hui » delegations the caller may see.

Writes go through /sync (table `task_delegations`, app/api/sync.py). This read is the app's
REPLACE refresh of its cache (docs/cache-conventions.md): every active delegation of a live
task of the current household that the caller sees, authored by anyone. Empty while the
household's `today` module is off. Spec: docs/plans/a-faire-aujourdhui.md.
"""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.services.delegations import visible_delegations
from app.utils.scoping import current_foyer_uuid

router = APIRouter(prefix="/api", tags=["delegations"])


class DelegationView(BaseModel):
    id: UUID
    task_id: UUID
    author_id: UUID
    recipient_ids: list[UUID]
    created_at: datetime


@router.get("/delegations", response_model=list[DelegationView])
async def list_delegations(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    return await visible_delegations(session, current_foyer_uuid(current), current["user_id"])
