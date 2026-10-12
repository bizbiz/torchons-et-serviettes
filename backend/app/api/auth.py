"""Auth routes: current profile and members of the active household.

Tokens are issued by onboarding, pairing and recovery only. The legacy
username / Tailscale-IP login routes were removed on 2026-10-05: they handed
out a token without any secret.
"""

from __future__ import annotations

from uuid import UUID

from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.models.user import User
from app.models.user_foyer import UserFoyer
from app.utils.errors import LocalizedHTTPException
from app.utils.scoping import current_foyer_uuid

router = APIRouter(prefix="/api/auth", tags=["auth"])


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
        .where(UserFoyer.foyer_id == foyer_uuid, UserFoyer.left_at.is_(None))
        .order_by(User.username)
    )
    users = result.scalars().all()
    return [{"id": str(u.id), "username": u.username, "display_name": u.display_name} for u in users]
