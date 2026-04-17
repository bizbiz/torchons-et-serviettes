"""Routes prompts — queue unifiee de questions a poser aux users.

GET  /api/prompts/next         : prochain prompt non-resolu (1 ou 204)
POST /api/prompts/{id}/shown   : marque le prompt comme affiche
POST /api/prompts/{id}/resolve : resout le prompt (DISMISSED / ANSWERED)
"""

from __future__ import annotations

import uuid as uuid_lib
from datetime import datetime, timezone
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Response
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.models.enums import PromptResolution, PromptType
from app.models.task_effort import TaskEffort
from app.models.user_prompt import UserPrompt
from app.utils.scoping import current_foyer_uuid

router = APIRouter(prefix="/api", tags=["prompts"])


# --- Schemas ---

class PromptResponse(BaseModel):
    id: UUID
    type: str
    priority: int
    context: dict
    created_at: datetime
    shown_at: datetime | None

    model_config = {"from_attributes": True}

    @classmethod
    def from_orm_prompt(cls, p: UserPrompt) -> "PromptResponse":
        return cls(
            id=p.id,
            type=p.type.value,
            priority=p.priority,
            context=p.context,
            created_at=p.created_at,
            shown_at=p.shown_at,
        )


class PromptResolveRequest(BaseModel):
    resolution: str  # "DISMISSED" ou "ANSWERED"
    payload: dict | None = None


# --- Endpoints ---

@router.get("/prompts/next")
async def get_next_prompt(
    response: Response,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Retourne le prochain prompt non-resolu (le plus prioritaire, le plus ancien).

    204 No Content si rien. Le front doit appeler POST /shown des l'affichage.
    """
    user_id = UUID(current["user_id"])
    foyer_uuid = current_foyer_uuid(current)
    now = datetime.now(timezone.utc)

    result = await session.execute(
        select(UserPrompt)
        .where(
            UserPrompt.user_id == user_id,
            UserPrompt.foyer_id == foyer_uuid,
            UserPrompt.resolved_at.is_(None),
            # Exclure les expires
            (UserPrompt.expires_at.is_(None)) | (UserPrompt.expires_at > now),
        )
        .order_by(UserPrompt.priority.desc(), UserPrompt.created_at.asc())
        .limit(1)
    )
    prompt = result.scalar_one_or_none()

    if prompt is None:
        response.status_code = 204
        return None

    return PromptResponse.from_orm_prompt(prompt)


@router.post("/prompts/{prompt_id}/shown")
async def mark_shown(
    prompt_id: UUID,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Marque le prompt comme affiche (shown_at). Idempotent."""
    user_id = UUID(current["user_id"])
    result = await session.execute(
        select(UserPrompt).where(
            UserPrompt.id == prompt_id,
            UserPrompt.user_id == user_id,
        )
    )
    prompt = result.scalar_one_or_none()
    if prompt is None:
        raise HTTPException(status_code=404, detail="Prompt introuvable")

    if prompt.shown_at is None:
        prompt.shown_at = datetime.now(timezone.utc)
        await session.commit()

    return {"ok": True}


@router.post("/prompts/{prompt_id}/resolve")
async def resolve_prompt(
    prompt_id: UUID,
    body: PromptResolveRequest,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Resout un prompt. Actions selon type + resolution.

    BUMP_EFFORT + ANSWERED + payload.action=="bump_effort" : incremente le
    vote effort de l'user de +1 pour la tache.

    VOTE_RATING + ANSWERED : le front a deja soumis via les endpoints
    existants (set_affinity, set_effort). On marque juste comme resolu.
    """
    user_id = UUID(current["user_id"])
    result = await session.execute(
        select(UserPrompt).where(
            UserPrompt.id == prompt_id,
            UserPrompt.user_id == user_id,
        )
    )
    prompt = result.scalar_one_or_none()
    if prompt is None:
        raise HTTPException(status_code=404, detail="Prompt introuvable")

    if prompt.resolved_at is not None:
        return {"ok": True, "already_resolved": True}

    # Resolution
    resolution = body.resolution.upper()
    if resolution not in ("DISMISSED", "ANSWERED"):
        raise HTTPException(status_code=400, detail="resolution must be DISMISSED or ANSWERED")

    prompt.resolved_at = datetime.now(timezone.utc)
    prompt.resolution = PromptResolution(resolution)

    # Actions specifiques
    payload = body.payload or {}
    if prompt.type == PromptType.BUMP_EFFORT and resolution == "ANSWERED":
        action = payload.get("action", "")
        if action == "bump_effort":
            task_id = prompt.context.get("task_id")
            if task_id:
                # Incrementer le vote effort de l'user de +1
                effort_result = await session.execute(
                    select(TaskEffort).where(
                        TaskEffort.task_id == uuid_lib.UUID(task_id),
                        TaskEffort.user_id == user_id,
                    )
                )
                effort = effort_result.scalar_one_or_none()
                if effort:
                    effort.points = min(18, effort.points + 1)
                else:
                    # Pas de vote existant — en creer un a 4 (defaut 3 + 1)
                    new_effort = TaskEffort(
                        task_id=uuid_lib.UUID(task_id),
                        user_id=user_id,
                        points=4,
                    )
                    session.add(new_effort)

    await session.commit()
    return {"ok": True}
