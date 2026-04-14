"""Routes presence — Feature 1 home freeze v0.7.4.

Le client Android POST ici a chaque fois qu'il detecte sa situation WiFi
(au demarrage de l'app ou via le PresenceWorker toutes les 6h). Le backend
met a jour l'etat du user + recompute l'occupation du foyer + detecte les
retours d'absence pour afficher le message "bon retour".
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.models.foyer import Foyer
from app.models.user import User
from app.models.user_foyer import UserFoyer
from app.utils.scoping import current_foyer_uuid

router = APIRouter(prefix="/api", tags=["presence"])


class PresenceUpdate(BaseModel):
    """Payload envoye par le client : is_at_home = True si sur un home SSID."""
    is_at_home: bool


class WelcomeBack(BaseModel):
    absence_days: int
    from_date: str  # ISO date
    to_date: str    # ISO date


class PresenceResponse(BaseModel):
    ok: bool
    foyer_occupied: bool
    welcome_back: WelcomeBack | None = None


# Seuil d'absence minimum pour declencher le welcome back message
# (coherent avec le seuil de freeze des 24h).
WELCOME_BACK_THRESHOLD_HOURS = 24


@router.post("/presence", response_model=PresenceResponse)
async def update_presence(
    body: PresenceUpdate,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Met a jour l'etat de presence du user courant et recompute l'etat
    du foyer. Retourne un welcome_back si le foyer etait inoccupe plus de
    24h avant ce report.

    Comportement :
    1. UPDATE users.is_currently_at_home + last_seen_at_home_at (si true)
    2. Recompute : foyer est occupe ssi AU MOINS UN user du foyer a
       is_currently_at_home=true.
    3. Si le foyer passe de non-occupe a occupe apres une absence > 24h,
       on prepare un welcome_back et on met a jour foyers.last_occupied_at.
    4. Sinon, si le foyer est occupe, on met a jour last_occupied_at = now
       (le foyer est "active" continuellement pendant l'occupation).
    """
    user_id = uuid.UUID(current["user_id"])
    foyer_uuid = current_foyer_uuid(current)
    now = datetime.now(timezone.utc)

    # 1. Update user state
    user_result = await session.execute(select(User).where(User.id == user_id))
    user = user_result.scalar_one_or_none()
    if user is None:
        return PresenceResponse(ok=False, foyer_occupied=False)

    user.is_currently_at_home = body.is_at_home
    if body.is_at_home:
        user.last_seen_at_home_at = now

    # 2. Load foyer + check occupancy BEFORE this update
    foyer_result = await session.execute(select(Foyer).where(Foyer.id == foyer_uuid))
    foyer = foyer_result.scalar_one_or_none()
    if foyer is None:
        await session.commit()
        return PresenceResponse(ok=False, foyer_occupied=False)

    previous_last_occupied = foyer.last_occupied_at

    # 3. Determiner si le foyer est maintenant occupe (au moins 1 user du
    # foyer avec is_currently_at_home=true). L'user courant vient d'etre
    # update donc la query reflete l'etat post-update.
    await session.flush()
    occupied_result = await session.execute(
        select(User.is_currently_at_home)
        .join(UserFoyer, UserFoyer.user_id == User.id)
        .where(
            UserFoyer.foyer_id == foyer_uuid,
            User.is_currently_at_home.is_(True),
        )
        .limit(1)
    )
    foyer_occupied = occupied_result.scalar_one_or_none() is not None

    welcome_back: WelcomeBack | None = None

    if foyer_occupied:
        # Detecter un retour d'absence long
        if previous_last_occupied is not None:
            absence = now - previous_last_occupied
            if absence.total_seconds() > WELCOME_BACK_THRESHOLD_HOURS * 3600:
                welcome_back = WelcomeBack(
                    absence_days=int(absence.total_seconds() // 86400),
                    from_date=previous_last_occupied.date().isoformat(),
                    to_date=now.date().isoformat(),
                )
        # Le foyer est occupe, on met a jour last_occupied_at = now
        foyer.last_occupied_at = now
    # Si foyer non-occupe : on ne touche pas a last_occupied_at, il reste
    # au timestamp du moment ou le dernier user est parti.

    await session.commit()

    return PresenceResponse(
        ok=True,
        foyer_occupied=foyer_occupied,
        welcome_back=welcome_back,
    )
