"""Routes auth — login simple + auto-login Tailscale."""

from __future__ import annotations

import json
from uuid import UUID

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import create_token, get_current_user
from app.config import settings
from app.database import get_session
from app.models.user import User
from app.models.user_foyer import UserFoyer
from app.utils.errors import LocalizedHTTPException
from app.utils.scoping import current_foyer_uuid

router = APIRouter(prefix="/api/auth", tags=["auth"])


class LoginRequest(BaseModel):
    username: str
    device_id: str


@router.post("/login")
async def login(body: LoginRequest, session: AsyncSession = Depends(get_session)):
    """Login par username. Retourne un JWT avec foyer_id = last_active_foyer_id."""
    result = await session.execute(select(User).where(User.username == body.username))
    user = result.scalar_one_or_none()
    if not user:
        raise LocalizedHTTPException(
            status_code=401,
            error_code="AUTH_USER_UNKNOWN_FORMAT",
            format_args={"username": body.username},
        )
    foyer_id = str(user.last_active_foyer_id) if user.last_active_foyer_id else None
    token = create_token(str(user.id), body.device_id, foyer_id=foyer_id)
    return {
        "token": token,
        "user": {"id": str(user.id), "username": user.username, "display_name": user.display_name},
        "foyer_id": foyer_id,
    }


def _get_client_ip(request: Request) -> str | None:
    """Recupere l'IP du client (supporte X-Forwarded-For)."""
    forwarded = request.headers.get("x-forwarded-for")
    if forwarded:
        return forwarded.split(",")[0].strip()
    return request.client.host if request.client else None


# Mapping IP -> username dynamique (persist en memoire + config initiale)
_ip_map: dict[str, str] = {}
_ip_map_loaded = False

def _get_ip_map() -> dict[str, str]:
    global _ip_map_loaded
    if not _ip_map_loaded:
        _ip_map.update(json.loads(settings.tailscale_map))
        _ip_map_loaded = True
    return _ip_map


@router.get("/auto")
async def auto_login(request: Request, session: AsyncSession = Depends(get_session)):
    """Auto-login via IP. Si IP inconnue, retourne la liste des users pour choix."""
    client_ip = _get_client_ip(request)
    ip_map = _get_ip_map()
    username = ip_map.get(client_ip)

    if not username:
        # IP inconnue — retourner la liste des users pour que le client choisisse
        result = await session.execute(select(User).order_by(User.username))
        users = result.scalars().all()
        return {
            "status": "unknown_ip",
            "ip": client_ip,
            "users": [{"id": str(u.id), "username": u.username, "display_name": u.display_name} for u in users],
        }

    result = await session.execute(select(User).where(User.username == username))
    user = result.scalar_one_or_none()
    if not user:
        raise LocalizedHTTPException(
            status_code=404,
            error_code="AUTH_USER_NOT_FOUND_FORMAT",
            format_args={"username": username},
        )

    foyer_id = str(user.last_active_foyer_id) if user.last_active_foyer_id else None
    token = create_token(str(user.id), f"tailscale-{client_ip}", foyer_id=foyer_id)
    return {
        "token": token,
        "user": {"id": str(user.id), "username": user.username, "display_name": user.display_name},
        "foyer_id": foyer_id,
    }


class ClaimIP(BaseModel):
    username: str


@router.post("/claim")
async def claim_ip(request: Request, body: ClaimIP, session: AsyncSession = Depends(get_session)):
    """Associe l'IP du client a un utilisateur. Pour les nouveaux appareils."""
    client_ip = _get_client_ip(request)
    ip_map = _get_ip_map()

    result = await session.execute(select(User).where(User.username == body.username))
    user = result.scalar_one_or_none()
    if not user:
        raise LocalizedHTTPException(
            status_code=404,
            error_code="AUTH_USER_NOT_FOUND_FORMAT",
            format_args={"username": body.username},
        )

    # Enregistrer le mapping
    ip_map[client_ip] = body.username

    foyer_id = str(user.last_active_foyer_id) if user.last_active_foyer_id else None
    token = create_token(str(user.id), f"tailscale-{client_ip}", foyer_id=foyer_id)
    return {
        "token": token,
        "user": {"id": str(user.id), "username": user.username, "display_name": user.display_name},
        "foyer_id": foyer_id,
    }


@router.get("/me")
async def me(
    current: dict = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    """Profil de l'utilisateur courant."""
    result = await session.execute(select(User).where(User.id == UUID(current["user_id"])))
    user = result.scalar_one_or_none()
    if not user:
        raise LocalizedHTTPException(status_code=404, error_code="USER_NOT_FOUND")
    return {"id": str(user.id), "username": user.username, "display_name": user.display_name}


@router.get("/users")
async def list_users(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Liste uniquement les utilisateurs du foyer actif (scope v0.7.x).

    Avant v0.7.2, cet endpoint retournait tous les users de la base, ce qui
    polluait les pickers (assignation de tache, "qui a fait ?") quand on
    creait des taches dans un nouveau foyer avec un seul membre.
    """
    foyer_uuid = current_foyer_uuid(current)
    result = await session.execute(
        select(User)
        .join(UserFoyer, UserFoyer.user_id == User.id)
        .where(UserFoyer.foyer_id == foyer_uuid)
        .order_by(User.username)
    )
    users = result.scalars().all()
    return [{"id": str(u.id), "username": u.username, "display_name": u.display_name} for u in users]
