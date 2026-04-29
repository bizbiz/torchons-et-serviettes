"""Endpoint RGPD : DELETE /me — soft-delete du compte courant (v0.30).

L'user authentifie supprime son compte. On marque `users.deleted_at = now()`
+ on soft-leave toutes ses memberships actives (avec auto-promote dans
chaque foyer comme un leave classique). Job daily (a implementer) purge
les rows physiquement apres 30 jours.

Pas de verification cote `get_current_user` que `deleted_at IS NULL` :
le user devra recreer un foyer ou rejoindre via QR pour reutiliser l'app.
La purge job J+30 cassera son JWT a ce moment-la (FK CASCADE).
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.models.user import User
from app.models.user_foyer import UserFoyer
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
        raise HTTPException(status_code=404, detail="Utilisateur introuvable")

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
