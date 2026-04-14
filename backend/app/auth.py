"""Auth JWT simple — token sans expiration, scope par foyer."""

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

_security = HTTPBearer()
ALGORITHM = "HS256"


def create_token(user_id: str, device_id: str, foyer_id: str | None = None) -> str:
    """Genere un JWT avec user_id, device_id et foyer_id actif."""
    payload = {"sub": user_id, "device_id": device_id}
    if foyer_id is not None:
        payload["foyer_id"] = foyer_id
    return jwt.encode(payload, settings.jwt_secret, algorithm=ALGORITHM)


def decode_token(token: str) -> dict:
    """Decode et valide un JWT. Retourne les claims."""
    try:
        return jwt.decode(token, settings.jwt_secret, algorithms=[ALGORITHM])
    except JWTError:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Token invalide")


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

    return {
        "user_id": user_id,
        "foyer_id": foyer_id,
        "device_id": claims.get("device_id", str(uuid.uuid4())),
    }
