"""Service lifecycle membre : soft-leave + auto-promotion (v0.30).

Decisions Q9 du design review — voir docs/design-onboarding-public.md §9.

Soft-leave : `user_foyers.left_at = now()` + role -> 'former_member'.
Historique (completions, votes) preserve. Si le partant etait le dernier
admin actif, on auto-promote le plus ancien member restant. Si aucun
member ne reste, on marque le foyer `archived_at = now()` (job daily
purgera apres 365j).

Le recompute de consensus (votes effort/frequence) en excluant les
former_members est intentionnellement non implemente ici. Les services
de calcul existants devront filtrer sur `UserFoyer.left_at IS NULL`
quand ils recoupent des votes ; un sweep explicite n'est pas necessaire
puisque les votes restent en base.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.foyer import Foyer
from app.models.user_foyer import UserFoyer


async def soft_leave_foyer(
    session: AsyncSession,
    user_id: uuid.UUID,
    foyer_id: uuid.UUID,
) -> bool:
    """Effectue le soft-leave + auto-promotion. Retourne True si l'user
    etait effectivement membre actif (et donc parti), False s'il etait
    deja former_member ou pas membre du tout (no-op)."""
    membership_result = await session.execute(
        select(UserFoyer).where(
            UserFoyer.user_id == user_id,
            UserFoyer.foyer_id == foyer_id,
            UserFoyer.left_at.is_(None),
        )
    )
    uf = membership_result.scalar_one_or_none()
    if uf is None:
        return False

    was_admin = uf.role == "admin"
    now = datetime.now(timezone.utc)
    uf.left_at = now
    uf.role = "former_member"

    if not was_admin:
        return True

    # Compte les admins actifs restants
    admins_remaining = (
        await session.execute(
            select(func.count(UserFoyer.user_id)).where(
                UserFoyer.foyer_id == foyer_id,
                UserFoyer.role == "admin",
                UserFoyer.left_at.is_(None),
            )
        )
    ).scalar_one()

    if admins_remaining > 0:
        return True

    # Promote oldest active member
    oldest_member_result = await session.execute(
        select(UserFoyer)
        .where(
            UserFoyer.foyer_id == foyer_id,
            UserFoyer.role == "member",
            UserFoyer.left_at.is_(None),
        )
        .order_by(UserFoyer.joined_at)
        .limit(1)
    )
    oldest = oldest_member_result.scalar_one_or_none()
    if oldest is not None:
        oldest.role = "admin"
        return True

    # Aucun member restant → foyer fantome, marque archived_at
    foyer_result = await session.execute(select(Foyer).where(Foyer.id == foyer_id))
    foyer = foyer_result.scalar_one_or_none()
    if foyer is not None and foyer.archived_at is None:
        foyer.archived_at = now
    return True
