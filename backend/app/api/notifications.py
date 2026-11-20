"""Routes notifications — enregistrement tokens FCM."""

from __future__ import annotations

from uuid import UUID

from fastapi import APIRouter, Depends
from pydantic import BaseModel, model_validator
from sqlalchemy import select, delete
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.models.notification import NotificationToken
from app.utils.row_lock import lock_key

router = APIRouter(prefix="/api/notifications", tags=["notifications"])


class RegisterToken(BaseModel):
    """`fid` (Firebase Installation ID) from the app since 2026-11-12; `fcm_token` from the APKs
    before it. Exactly one of the two."""
    fcm_token: str | None = None
    fid: str | None = None
    device_name: str | None = None
    platform: str = "android"

    @model_validator(mode="after")
    def _one_target(self):
        if bool(self.fcm_token) == bool(self.fid):
            raise ValueError("exactly one of fcm_token or fid")
        return self

    @property
    def target(self) -> tuple[str, str]:
        return ("fid", self.fid) if self.fid else ("token", self.fcm_token)


@router.post("/register")
async def register_token(
    body: RegisterToken,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Enregistre ou met a jour la cible de notification (jeton FCM ou FID) de l'appareil."""
    user_id = UUID(current["user_id"])
    kind, value = body.target

    # The app registers on every launch and may resend: two copies must not both insert.
    await lock_key(session, f"notification-target:{user_id}:{value}")
    # Verifier si la cible existe deja
    result = await session.execute(
        select(NotificationToken).where(
            NotificationToken.user_id == user_id,
            NotificationToken.fcm_token == value,
        )
    )
    existing = result.scalar_one_or_none()

    # `device_id` vient du JWT, jamais du corps : le client ne peut pas mentir
    # dessus sans mentir sur son jeton, et surtout cela couvre les APK DEJA
    # installes sans attendre une mise a jour de l'app. C'est ce qui permet de
    # couper les notifications quand l'appareil est revoque.
    device_id = current.get("device_id")

    if kind == "fid" and device_id:
        # The app that now sends its FID used to send a token: that row would notify the same
        # phone a second time, until FCM declared it dead.
        await session.execute(delete(NotificationToken).where(
            NotificationToken.user_id == user_id,
            NotificationToken.device_id == device_id,
            NotificationToken.kind == "token",
        ))

    if existing:
        existing.device_name = body.device_name or existing.device_name
        existing.platform = body.platform
        existing.kind = kind
        # Rattache les lignes creees avant cette colonne, au premier passage.
        if device_id:
            existing.device_id = device_id
    else:
        session.add(NotificationToken(
            user_id=user_id,
            fcm_token=value,
            kind=kind,
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
    """Supprime une cible de notification (jeton FCM ou FID)."""
    user_id = UUID(current["user_id"])
    await session.execute(
        delete(NotificationToken).where(
            NotificationToken.user_id == user_id,
            NotificationToken.fcm_token == body.target[1],
        )
    )
    await session.commit()
    return {"ok": True}
