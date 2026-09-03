"""Endpoints devices — see and cut off the devices holding your session.

Scope: a user sees and revokes their OWN devices only. `UserFoyer.role`
exists but is deliberately not consulted -- the product has no asymmetric
power between household members today, and device revocation is not the
feature that should introduce one.

The silent transition (`/register`) is the piece that makes this work
without bothering anyone. On Android the `device_id` lives in a DataStore
excluded from Auto Backup, so a phone restored onto a new handset finds no
identity and mints a fresh one, while still holding the restored (valid)
token. It presents both here: the token proves who the user is, the new id
says "I am a different handset". The server records the new device and
hands back a token bound to it. The old phone keeps its own row, so the two
remain separately revocable -- and the user saw nothing.

Full rationale and the arbitrations behind it: `docs/appareils.fr.md`.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field
from sqlalchemy import delete, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import create_token, get_current_user
from app.database import get_session
from app.models.device import Device
from app.models.notification import NotificationToken
from app.utils.devices import resolve_device
from app.utils.errors import LocalizedHTTPException

router = APIRouter(prefix="/api/devices", tags=["devices"])


class DeviceOut(BaseModel):
    device_id: str
    device_name: str | None
    platform: str
    first_seen: datetime
    last_seen: datetime
    revoked_at: datetime | None
    # Lets the client mark "this device" without comparing ids itself --
    # the only reliable discriminator when two handsets share a model name.
    is_current: bool


class RegisterRequest(BaseModel):
    device_id: str = Field(min_length=1, max_length=64)
    device_name: str | None = Field(default=None, max_length=200)


class RegisterResponse(BaseModel):
    # Non-null only when the server adopted a NEW device_id and rebound the
    # session to it. The client stores it and keeps going; nothing is shown.
    new_token: str | None = None


@router.get("", response_model=list[DeviceOut])
async def list_devices(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
) -> list[DeviceOut]:
    """Devices holding a session for the calling user, newest first."""
    rows = (
        await session.execute(
            select(Device)
            .where(Device.user_id == uuid.UUID(current["user_id"]))
            .order_by(Device.first_seen.desc())
        )
    ).scalars().all()
    return [
        DeviceOut(
            device_id=r.device_id,
            device_name=r.device_name,
            platform=r.platform,
            first_seen=r.first_seen,
            last_seen=r.last_seen,
            revoked_at=r.revoked_at,
            is_current=(r.device_id == current["device_id"]),
        )
        for r in rows
    ]


@router.post("/register", response_model=RegisterResponse)
async def register_device(
    body: RegisterRequest,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
) -> RegisterResponse:
    """Declare the device_id this installation actually holds.

    Called at startup. When it differs from the one inside the token, the
    session is rebound to the new device and a fresh token is returned.
    `get_current_user` has already refused the call if the token's device
    was revoked, so a revoked phone cannot mint itself a new identity here.
    """
    token_device_id = current["device_id"]
    await resolve_device(session, current["user_id"], body.device_id, body.device_name)

    if body.device_id == token_device_id:
        await session.commit()
        return RegisterResponse(new_token=None)

    new_token = create_token(
        user_id=current["user_id"],
        device_id=body.device_id,
        foyer_id=current["foyer_id"],
    )
    await session.commit()
    return RegisterResponse(new_token=new_token)


@router.post("/{device_id}/revoke", status_code=204)
async def revoke_device(
    device_id: str,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
) -> None:
    """Cut off one of the caller's OTHER devices."""
    if device_id == current["device_id"]:
        raise LocalizedHTTPException(
            status_code=400, error_code="DEVICE_CANNOT_REVOKE_SELF"
        )
    row = (
        await session.execute(
            select(Device).where(
                Device.user_id == uuid.UUID(current["user_id"]),
                Device.device_id == device_id,
            )
        )
    ).scalar_one_or_none()
    if row is None:
        raise LocalizedHTTPException(status_code=404, error_code="DEVICE_NOT_FOUND")
    if row.revoked_at is None:
        row.revoked_at = datetime.now(timezone.utc)
    await _drop_push_tokens(session, current["user_id"], [device_id])
    await session.commit()


@router.post("/me/signout", status_code=204)
async def signout_this_device(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
) -> None:
    """Sign out and revoke the device making the call.

    Separate from `/revoke` on purpose: the caller is cutting its own
    branch, so the client must erase its local data in the same gesture,
    and the UI wording differs.
    """
    await session.execute(
        update(Device)
        .where(
            Device.user_id == uuid.UUID(current["user_id"]),
            Device.device_id == current["device_id"],
            Device.revoked_at.is_(None),
        )
        .values(revoked_at=datetime.now(timezone.utc))
    )
    await _drop_push_tokens(session, current["user_id"], [current["device_id"]])
    await session.commit()


async def _drop_push_tokens(
    session: AsyncSession, user_id: str, device_ids: list[str]
) -> int:
    """Supprime les jetons FCM des appareils coupes.

    Sans cela, revoquer un appareil coupait son API et lui laissait les
    notifications du foyer — titres de taches, qui a fait quoi. Le chemin
    avait ete enumere avant d'ecrire les tests (`test_devices.py`, chemin 5)
    et laisse ouvert faute de lien entre un jeton FCM et un appareil ; la
    colonne `notification_tokens.device_id` le fournit.

    Les lignes anterieures a cette colonne ont `device_id` a NULL : elles ne
    sont rattachees a personne et se rattachent d'elles-memes au prochain
    enregistrement FCM, qui lit le JWT.
    """
    if not device_ids:
        return 0
    result = await session.execute(
        delete(NotificationToken).where(
            NotificationToken.user_id == uuid.UUID(user_id),
            NotificationToken.device_id.in_(device_ids),
        )
    )
    return result.rowcount or 0


async def revoke_other_devices(
    session: AsyncSession, user_id: str, keep_device_id: str
) -> int:
    """Revoke every device of this user except `keep_device_id`.

    Used by `/recovery/regenerate`: a leaked recovery code means every
    other door must close in the same gesture. Returns the row count so
    the caller can tell the user how many were cut.
    """
    result = await session.execute(
        update(Device)
        .where(
            Device.user_id == uuid.UUID(user_id),
            Device.device_id != keep_device_id,
            Device.revoked_at.is_(None),
        )
        .values(revoked_at=datetime.now(timezone.utc))
    )
    # Les notifications suivent la meme coupure, PAR LE MEME chemin que les
    # revocations unitaires : une seconde implementation du meme delete avait
    # deja diverge — le controle negatif du 03/09 laissait ce cas vert alors
    # qu'il debranchait la regle.
    coupes = (
        await session.execute(
            select(Device.device_id).where(
                Device.user_id == uuid.UUID(user_id),
                Device.device_id != keep_device_id,
            )
        )
    ).scalars().all()
    await _drop_push_tokens(session, user_id, list(coupes))
    return result.rowcount or 0
