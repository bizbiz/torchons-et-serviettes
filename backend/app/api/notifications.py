"""Routes notifications — enregistrement tokens FCM."""

from __future__ import annotations

from uuid import UUID

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy import select, delete
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.models.notification import NotificationToken

router = APIRouter(prefix="/api/notifications", tags=["notifications"])


class RegisterToken(BaseModel):
    fcm_token: str
    device_name: str | None = None
    platform: str = "android"


@router.post("/register")
async def register_token(
    body: RegisterToken,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Enregistre ou met a jour un token FCM pour l'utilisateur courant."""
    user_id = UUID(current["user_id"])

    # Verifier si le token existe deja
    result = await session.execute(
        select(NotificationToken).where(
            NotificationToken.user_id == user_id,
            NotificationToken.fcm_token == body.fcm_token,
        )
    )
    existing = result.scalar_one_or_none()

    # `device_id` vient du JWT, jamais du corps : le client ne peut pas mentir
    # dessus sans mentir sur son jeton, et surtout cela couvre les APK DEJA
    # installes sans attendre une mise a jour de l'app. C'est ce qui permet de
    # couper les notifications quand l'appareil est revoque.
    device_id = current.get("device_id")

    if existing:
        existing.device_name = body.device_name or existing.device_name
        existing.platform = body.platform
        # Rattache les lignes creees avant cette colonne, au premier passage.
        if device_id:
            existing.device_id = device_id
    else:
        session.add(NotificationToken(
            user_id=user_id,
            fcm_token=body.fcm_token,
            device_name=body.device_name,
            device_id=device_id,
            platform=body.platform,
        ))

    await session.commit()
    return {"ok": True}


@router.delete("/register")
async def unregister_token(
    body: RegisterToken,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Supprime un token FCM."""
    user_id = UUID(current["user_id"])
    await session.execute(
        delete(NotificationToken).where(
            NotificationToken.user_id == user_id,
            NotificationToken.fcm_token == body.fcm_token,
        )
    )
    await session.commit()
    return {"ok": True}
