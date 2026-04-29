"""Endpoints QR pairing — flow "Rejoindre un foyer" (v0.30).

Pattern : owner authentifie genere un invite_token ephemere via init, le
client (Android) l'encode dans un QR. Le joiner installe l'app, scanne le
QR, fournit son display_name et appelle join. Le backend cree User +
UserFoyer + recovery_code, puis renvoie le JWT.

Voir docs/design-onboarding-public.md §3 pour la decision de fond.
"""

from __future__ import annotations

import secrets
import uuid
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import create_token, get_current_user
from app.database import get_session
from app.models.foyer import Foyer
from app.models.pairing_token import PairingToken
from app.models.user import User
from app.models.user_foyer import UserFoyer
from app.utils.recovery_code import generate_code, hash_code

router = APIRouter(prefix="/api/pairing", tags=["pairing"])

# TTL d'un token de pairing (decision Q3 du grill-me).
_TOKEN_TTL = timedelta(minutes=15)


class PairingInitResponse(BaseModel):
    invite_token: str
    expires_at: datetime
    foyer_name: str  # affiche dans l'UI owner pour confirmation visuelle


class PairingJoinRequest(BaseModel):
    invite_token: str
    display_name: str = Field(min_length=1, max_length=100)


class PairingJoinResponse(BaseModel):
    """Reponse au join. Inclut le recovery_code en clair UNE SEULE FOIS pour
    l'afficher au joiner — il sera ensuite seulement stocke en hash."""
    token: str
    user_id: str
    foyer_id: str
    foyer_name: str
    recovery_code: str


@router.post("/init", response_model=PairingInitResponse)
async def init_pairing(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Owner genere un nouveau token pour inviter quelqu'un dans son foyer
    actif. Token aleatoire 32 chars hex (16 octets), TTL 15 min, single-use.

    L'owner peut en generer plusieurs successivement (chacun usage unique).
    Pas de revocation explicite — on attend l'expiration ou la consommation.
    """
    user_id = uuid.UUID(current["user_id"])
    foyer_id = uuid.UUID(current["foyer_id"])

    # Verifie que le user est bien membre actif du foyer (pas de left_at).
    membership = await session.execute(
        select(UserFoyer).where(
            UserFoyer.user_id == user_id,
            UserFoyer.foyer_id == foyer_id,
            UserFoyer.left_at.is_(None),
        )
    )
    if membership.scalar_one_or_none() is None:
        raise HTTPException(
            status_code=403,
            detail="Tu n'es pas membre actif de ce foyer",
        )

    foyer_result = await session.execute(select(Foyer).where(Foyer.id == foyer_id))
    foyer = foyer_result.scalar_one_or_none()
    if foyer is None or foyer.deleted_at is not None:
        raise HTTPException(status_code=404, detail="Foyer introuvable")

    token_value = secrets.token_hex(16)  # 32 chars hex
    expires_at = datetime.now(timezone.utc) + _TOKEN_TTL

    pt = PairingToken(
        token=token_value,
        foyer_id=foyer_id,
        created_by_user_id=user_id,
        expires_at=expires_at,
    )
    session.add(pt)
    await session.commit()

    return PairingInitResponse(
        invite_token=token_value,
        expires_at=expires_at,
        foyer_name=foyer.name,
    )


@router.post("/join", response_model=PairingJoinResponse)
async def join_pairing(
    body: PairingJoinRequest,
    session: AsyncSession = Depends(get_session),
):
    """Joiner consomme un invite_token et rejoint le foyer comme nouveau
    membre. Cree User + UserFoyer(role=member) + recovery_code.

    Endpoint unauthenticated : le joiner n'a pas encore de JWT (il vient
    d'installer l'app). Le invite_token tient lieu d'autorisation.

    Le device_id du joiner n'est pas requis ici : le backend le genere
    dans le JWT cote token-emit. Le client peut ensuite re-emettre via
    auth/me s'il prefere son propre device_id stable.
    """
    # Lookup token
    token_result = await session.execute(
        select(PairingToken).where(PairingToken.token == body.invite_token)
    )
    pt = token_result.scalar_one_or_none()
    if pt is None:
        raise HTTPException(status_code=404, detail="Code d'invitation invalide")

    # Validation TTL + single-use
    now = datetime.now(timezone.utc)
    if pt.consumed_at is not None:
        raise HTTPException(
            status_code=409, detail="Ce code d'invitation a deja ete utilise"
        )
    # `expires_at` est timezone-aware (DateTime(timezone=True)).
    if pt.expires_at < now:
        raise HTTPException(
            status_code=410, detail="Ce code d'invitation a expire"
        )

    # Recupere foyer pour validation + retour
    foyer_result = await session.execute(select(Foyer).where(Foyer.id == pt.foyer_id))
    foyer = foyer_result.scalar_one_or_none()
    if foyer is None or foyer.deleted_at is not None:
        raise HTTPException(
            status_code=410, detail="Le foyer associe a ce code n'existe plus"
        )

    # Cree le User (username auto-genere — le display_name est ce qui compte
    # cote UI, le username sera retire a la bascule publique cf. design doc).
    auto_username = f"u_{secrets.token_hex(6)}"
    new_user = User(
        username=auto_username,
        display_name=body.display_name.strip(),
        last_active_foyer_id=pt.foyer_id,
    )
    session.add(new_user)
    await session.flush()

    # Cree la membership
    session.add(
        UserFoyer(
            user_id=new_user.id,
            foyer_id=pt.foyer_id,
            role="member",
        )
    )

    # Genere le recovery code (Q5 du grill-me)
    recovery_code = generate_code()
    new_user.recovery_code_hash = hash_code(recovery_code)

    # Marque le token consomme
    pt.consumed_at = now

    await session.commit()
    await session.refresh(new_user)

    # Genere un JWT. Pas de device_id stable cote backend — on en met un
    # aleatoire ; le client peut le remplacer via son propre flow d'install.
    device_id = str(uuid.uuid4())
    jwt_token = create_token(
        str(new_user.id), device_id, foyer_id=str(pt.foyer_id)
    )

    return PairingJoinResponse(
        token=jwt_token,
        user_id=str(new_user.id),
        foyer_id=str(pt.foyer_id),
        foyer_name=foyer.name,
        recovery_code=recovery_code,
    )
