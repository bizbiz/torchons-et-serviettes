"""Auth JWT simple — token sans expiration, scope par foyer.

Le jeton n'expire pas (choix assume : une app de foyer ne redemande pas
un code toutes les semaines). Ce qui le rend revocable malgre tout est
la table `devices` : chaque appel authentifie verifie que l'appareil
porte par le jeton n'a pas ete revoque. Detail et arbitrages :
`docs/appareils.fr.md`.
"""

from __future__ import annotations

import uuid

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from jose import JWTError, jwt
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database import get_session
from app.models.user import User
from app.utils.devices import resolve_device

_security = HTTPBearer()
ALGORITHM = "HS256"


def create_token(user_id: str, device_id: str, foyer_id: str | None = None) -> str:
    """Genere un JWT avec user_id, device_id et foyer_id actif."""
    payload = {"sub": user_id, "device_id": device_id}
    if foyer_id is not None:
        payload["foyer_id"] = foyer_id
    return jwt.encode(payload, settings.jwt_secret, algorithm=ALGORITHM)


def decode_token(token: str) -> dict:
    """Decode and validate a session JWT; return its claims.

    A token that does not verify answers `DEVICE_REVOKED`, not an ordinary 401: the
    app wipes and goes back to the welcome screen instead of staying stuck on errors.
    This is what makes rotating `JWT_SECRET` survivable -- every phone holding a token
    signed with the old secret lands on the welcome screen, where « Retrouver mon
    foyer » brings it back (decided on 2026-11-02, option (b) of the todo item on the
    exposed production secret).
    """
    from app.utils.errors import LocalizedHTTPException

    try:
        return jwt.decode(token, settings.jwt_secret, algorithms=[ALGORITHM])
    except JWTError:
        raise LocalizedHTTPException(status_code=401, error_code="DEVICE_REVOKED")


async def _require_active_membership(session: AsyncSession, user_id: str, foyer_id: str) -> None:
    """Refuse a token whose user is deleted or no longer a member of its household.

    The JWT has no expiry (docs/appareils.fr.md), so it is only as good as this
    check. Without it, a member who left -- or whose account was deleted -- kept
    reading and writing the household with the token they already had.

    The refusal is `DEVICE_REVOKED`, the one 401 on which the app wipes its local
    data and goes back to the welcome screen: an ordinary 401 would leave the
    phone stuck on errors. Decided on 2026-10-05: a phone still on a household the
    user left elsewhere is revoked too; « Retrouver mon foyer » brings it back on
    the user's other household.
    """
    from app.models.user_foyer import UserFoyer
    from app.utils.errors import LocalizedHTTPException

    active = await session.execute(
        select(UserFoyer.user_id)
        .join(User, User.id == UserFoyer.user_id)
        .where(
            UserFoyer.user_id == uuid.UUID(user_id),
            UserFoyer.foyer_id == uuid.UUID(foyer_id),
            UserFoyer.left_at.is_(None),
            User.deleted_at.is_(None),
        )
    )
    if active.first() is None:
        raise LocalizedHTTPException(status_code=401, error_code="DEVICE_REVOKED")


async def get_current_user(
    creds: HTTPAuthorizationCredentials = Depends(_security),
    session: AsyncSession = Depends(get_session),
) -> dict:
    """Dependency FastAPI — retourne {user_id, foyer_id, device_id} depuis le JWT.

    Si le JWT ne contient pas foyer_id (ancien token pre-v0.7.0), on fallback
    sur users.last_active_foyer_id. Ca permet aux clients avec un ancien
    token de continuer a fonctionner sans forcer une reconnexion.
    """
    claims = decode_token(creds.credentials)
    # A scoped token (the 5-minute restore token between /recovery/restore and
    # /confirm) is not a session: without this, it opened every route.
    if claims.get("scope") is not None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Token invalide")
    user_id = claims["sub"]
    foyer_id = claims.get("foyer_id")

    if foyer_id is None:
        # Fallback : lire depuis users.last_active_foyer_id
        result = await session.execute(
            select(User.last_active_foyer_id).where(User.id == uuid.UUID(user_id))
        )
        last_active = result.scalar_one_or_none()
        if last_active is None:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Aucun foyer actif pour cet utilisateur",
            )
        foyer_id = str(last_active)

    await _require_active_membership(session, user_id, foyer_id)

    device_id = claims.get("device_id")

    # Un jeton legacy sans device_id ne resout AUCUN appareil. Le defaut
    # historique fabriquait un UUID neuf a chaque appel : brancher la
    # resolution dessus aurait cree une ligne devices PAR REQUETE.
    if device_id is not None:
        await resolve_device(session, user_id, device_id)

    return {
        "user_id": user_id,
        "foyer_id": foyer_id,
        "device_id": device_id or str(uuid.uuid4()),
    }
