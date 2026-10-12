"""Endpoint RGPD : DELETE /me — soft-delete du compte courant (v0.30).

L'user authentifie supprime son compte. On marque `users.deleted_at = now()`
+ on soft-leave toutes ses memberships actives (avec auto-promote dans
chaque foyer comme un leave classique). Job daily (a implementer) purge
les rows physiquement apres 30 jours.

Depuis le 2026-10-05, `get_current_user` refuse le jeton d'un compte supprime
avec `DEVICE_REVOKED` : chaque telephone du compte s'efface et revient a
l'accueil des son prochain appel. Avant, le jeton restait valide jusqu'a la
purge J+30.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.models.user import User
from app.models.user_foyer import UserFoyer
from app.utils.errors import LocalizedHTTPException
from app.services.lifecycle import soft_leave_foyer

router = APIRouter(prefix="/api/me", tags=["me"])


@router.delete("", status_code=204)
async def delete_me(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Soft-delete RGPD : marque deleted_at + soft-leave de tous les foyers."""
    user_id = uuid.UUID(current["user_id"])

    user_result = await session.execute(select(User).where(User.id == user_id))
    user = user_result.scalar_one_or_none()
    if user is None or user.deleted_at is not None:
        raise LocalizedHTTPException(status_code=404, error_code="USER_NOT_FOUND")

    # Liste les memberships actives a quitter
    memberships_result = await session.execute(
        select(UserFoyer.foyer_id).where(
            UserFoyer.user_id == user_id,
            UserFoyer.left_at.is_(None),
        )
    )
    foyer_ids = [row[0] for row in memberships_result.all()]

    for fid in foyer_ids:
        await soft_leave_foyer(session, user_id, fid)

    user.deleted_at = datetime.now(timezone.utc)
    user.last_active_foyer_id = None
    await session.commit()
