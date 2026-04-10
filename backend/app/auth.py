"""Auth JWT simple — 2 utilisateurs, token sans expiration."""

from __future__ import annotations

import uuid

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from jose import JWTError, jwt

from app.config import settings

_security = HTTPBearer()
ALGORITHM = "HS256"


def create_token(user_id: str, device_id: str) -> str:
    """Genere un JWT avec user_id et device_id."""
    return jwt.encode(
        {"sub": user_id, "device_id": device_id},
        settings.jwt_secret,
        algorithm=ALGORITHM,
    )


def decode_token(token: str) -> dict:
    """Decode et valide un JWT. Retourne les claims."""
    try:
        return jwt.decode(token, settings.jwt_secret, algorithms=[ALGORITHM])
    except JWTError:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Token invalide")


async def get_current_user(creds: HTTPAuthorizationCredentials = Depends(_security)) -> dict:
    """Dependency FastAPI — retourne {user_id, device_id} depuis le JWT."""
    claims = decode_token(creds.credentials)
    return {"user_id": claims["sub"], "device_id": claims.get("device_id", str(uuid.uuid4()))}
