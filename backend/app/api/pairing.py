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
from app.utils.errors import LocalizedHTTPException
from app.utils.recovery_code import generate_code, hash_code

router = APIRouter(prefix="/api/pairing", tags=["pairing"])

# TTL d'un token de pairing (decision Q3 du design review).
_TOKEN_TTL = timedelta(minutes=15)


class PairingInitResponse(BaseModel):
    invite_token: str
    expires_at: datetime
    foyer_name: str  # affiche dans l'UI owner pour confirmation visuelle
    kind: str  # 'foyer_join' (defaut) ou 'add_device' (v0.31)


class PairingJoinRequest(BaseModel):
    invite_token: str
    # display_name requis uniquement pour kind=foyer_join. Ignore pour
    # kind=add_device (le user existe deja, on ne change pas son nom).
    display_name: str = Field(default="", max_length=100)
    # device_id du device qui rejoint. Pour foyer_join, le backend en genere
    # un aleatoire si non fourni. Pour add_device, le client doit fournir
    # son device_id stable pour que le JWT soit lie au bon device.
    device_id: str | None = None


class PairingJoinResponse(BaseModel):
    """Reponse au join. Pour foyer_join, recovery_code est present (a
    afficher une seule fois). Pour add_device, recovery_code est null
    (l'user a deja le sien)."""
    token: str
    user_id: str
    foyer_id: str
    foyer_name: str
    kind: str  # echo du kind du token consomme
    # Inclus uniquement pour kind=foyer_join (nouveau User cree).
    recovery_code: str | None = None
    # display_name du user ; pour add_device c'est l'existant, pour
    # foyer_join c'est celui fourni dans la requete.
    display_name: str


async def _create_pairing_token(
    session: AsyncSession, user_id: uuid.UUID, foyer_id: uuid.UUID, kind: str,
) -> tuple[PairingToken, Foyer]:
    """Helper partage entre /init et /init-add-device. Verifie membership
    active + cree le token + retourne aussi le Foyer pour /init-add-device
    qui voudra son nom."""
    membership = await session.execute(
        select(UserFoyer).where(
            UserFoyer.user_id == user_id,
            UserFoyer.foyer_id == foyer_id,
            UserFoyer.left_at.is_(None),
        )
    )
    if membership.scalar_one_or_none() is None:
        raise LocalizedHTTPException(
            status_code=403,
            error_code="FOYER_NOT_ACTIVE_MEMBER",
        )

    foyer_result = await session.execute(select(Foyer).where(Foyer.id == foyer_id))
    foyer = foyer_result.scalar_one_or_none()
    if foyer is None or foyer.deleted_at is not None:
        raise LocalizedHTTPException(status_code=404, error_code="FOYER_NOT_FOUND")

    token_value = secrets.token_hex(16)  # 32 chars hex
    expires_at = datetime.now(timezone.utc) + _TOKEN_TTL

    pt = PairingToken(
        token=token_value,
        foyer_id=foyer_id,
        created_by_user_id=user_id,
        expires_at=expires_at,
        kind=kind,
    )
    session.add(pt)
    await session.commit()
    return pt, foyer


@router.post("/init", response_model=PairingInitResponse)
async def init_pairing(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Owner genere un nouveau token pour inviter quelqu'un dans son foyer
    actif. Token aleatoire 32 chars hex (16 octets), TTL 15 min, single-use.

    Cree un token kind='foyer_join' : le scan creera un nouveau User dans
    le foyer. Pour ajouter un de ses propres appareils, voir
    /init-add-device.
    """
    user_id = uuid.UUID(current["user_id"])
    foyer_id = uuid.UUID(current["foyer_id"])
    pt, foyer = await _create_pairing_token(session, user_id, foyer_id, "foyer_join")
    return PairingInitResponse(
        invite_token=pt.token,
        expires_at=pt.expires_at,
        foyer_name=foyer.name,
        kind=pt.kind,
    )


@router.post("/init-add-device", response_model=PairingInitResponse)
async def init_add_device(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """v0.31 — Owner genere un token pour ajouter un de ses propres
    appareils (tablette, 2nd tel) a son compte. Le scan emettra un JWT
    pour le MEME user_id, sans creer de nouveau User ni UserFoyer.

    Cf. design-onboarding-public.md §2 cas (B).
    """
    user_id = uuid.UUID(current["user_id"])
    foyer_id = uuid.UUID(current["foyer_id"])
    pt, foyer = await _create_pairing_token(session, user_id, foyer_id, "add_device")
    return PairingInitResponse(
        invite_token=pt.token,
        expires_at=pt.expires_at,
        foyer_name=foyer.name,
        kind=pt.kind,
    )


class PairingInfoResponse(BaseModel):
    """Probe sans consommation : permet au joiner de connaitre le kind du QR
    avant de soumettre /join, pour adapter l'UI (afficher la saisie
    display_name pour foyer_join, la sauter pour add_device)."""
    kind: str
    foyer_name: str
    expires_at: datetime


@router.get("/info/{invite_token}", response_model=PairingInfoResponse)
async def pairing_info(
    invite_token: str,
    session: AsyncSession = Depends(get_session),
):
    """Inspecte un invite_token sans le consommer. Unauth.

    Retourne le kind ('foyer_join' / 'add_device') + le nom du foyer + la
    date d'expiration. Le joiner appelle ca apres avoir scanne pour savoir
    s'il doit demander un display_name (foyer_join) ou non (add_device).
    """
    token_result = await session.execute(
        select(PairingToken).where(PairingToken.token == invite_token)
    )
    pt = token_result.scalar_one_or_none()
    if pt is None:
        raise LocalizedHTTPException(status_code=404, error_code="PAIRING_TOKEN_INVALID")
    if pt.consumed_at is not None:
        raise LocalizedHTTPException(
            status_code=409, error_code="PAIRING_TOKEN_USED"
        )
    now = datetime.now(timezone.utc)
    if pt.expires_at < now:
        raise LocalizedHTTPException(
            status_code=410, error_code="PAIRING_TOKEN_EXPIRED"
        )

    foyer_result = await session.execute(select(Foyer).where(Foyer.id == pt.foyer_id))
    foyer = foyer_result.scalar_one_or_none()
    if foyer is None or foyer.deleted_at is not None:
        raise LocalizedHTTPException(
            status_code=410, error_code="PAIRING_FOYER_GONE"
        )

    return PairingInfoResponse(
        kind=pt.kind,
        foyer_name=foyer.name,
        expires_at=pt.expires_at,
    )


@router.post("/join", response_model=PairingJoinResponse)
async def join_pairing(
    body: PairingJoinRequest,
    session: AsyncSession = Depends(get_session),
):
    """Joiner consomme un invite_token et rejoint le foyer.

    Comportement selon le `kind` du token :
    - `foyer_join` (defaut) : cree un nouveau User + UserFoyer(member) +
      recovery_code. `display_name` requis. Renvoie le recovery_code en
      clair une seule fois.
    - `add_device` (v0.31) : retourne un JWT pour le MEME user_id que le
      `created_by_user_id` du token. Pas de nouveau User, pas de
      recovery_code. `display_name` ignore (l'existant est conserve).

    Endpoint unauthenticated : le joiner n'a pas encore de JWT.
    """
    # Lookup token
    token_result = await session.execute(
        select(PairingToken).where(PairingToken.token == body.invite_token)
    )
    pt = token_result.scalar_one_or_none()
    if pt is None:
        raise LocalizedHTTPException(status_code=404, error_code="PAIRING_TOKEN_INVALID")

    # Validation TTL + single-use
    now = datetime.now(timezone.utc)
    if pt.consumed_at is not None:
        raise LocalizedHTTPException(
            status_code=409, error_code="PAIRING_TOKEN_USED"
        )
    if pt.expires_at < now:
        raise LocalizedHTTPException(
            status_code=410, error_code="PAIRING_TOKEN_EXPIRED"
        )

    # Recupere foyer pour validation + retour
    foyer_result = await session.execute(select(Foyer).where(Foyer.id == pt.foyer_id))
    foyer = foyer_result.scalar_one_or_none()
    if foyer is None or foyer.deleted_at is not None:
        raise LocalizedHTTPException(
            status_code=410, error_code="PAIRING_FOYER_GONE"
        )

    device_id = body.device_id or str(uuid.uuid4())

    if pt.kind == "add_device":
        # Cas v0.31 : on retourne un JWT pour l'user existant. Pas de nouveau
        # User. Verifie que l'user existant est encore valide (pas RGPD-supp).
        user_result = await session.execute(
            select(User).where(User.id == pt.created_by_user_id)
        )
        existing_user = user_result.scalar_one_or_none()
        if existing_user is None or existing_user.deleted_at is not None:
            raise LocalizedHTTPException(
                status_code=410,
                error_code="PAIRING_ACCOUNT_GONE",
            )
        pt.consumed_at = now
        await session.commit()

        jwt_token = create_token(
            str(existing_user.id), device_id, foyer_id=str(pt.foyer_id)
        )
        return PairingJoinResponse(
            token=jwt_token,
            user_id=str(existing_user.id),
            foyer_id=str(pt.foyer_id),
            foyer_name=foyer.name,
            kind=pt.kind,
            recovery_code=None,
            display_name=existing_user.display_name,
        )

    # Cas defaut foyer_join (existant v0.30)
    if not body.display_name.strip():
        raise LocalizedHTTPException(
            status_code=400,
            error_code="PAIRING_DISPLAY_NAME_REQUIRED",
        )

    auto_username = f"u_{secrets.token_hex(6)}"
    new_user = User(
        username=auto_username,
        display_name=body.display_name.strip(),
        last_active_foyer_id=pt.foyer_id,
    )
    session.add(new_user)
    await session.flush()

    session.add(
        UserFoyer(
            user_id=new_user.id,
            foyer_id=pt.foyer_id,
            role="member",
        )
    )

    recovery_code = generate_code()
    new_user.recovery_code_hash = hash_code(recovery_code)
    pt.consumed_at = now

    await session.commit()
    await session.refresh(new_user)

    jwt_token = create_token(
        str(new_user.id), device_id, foyer_id=str(pt.foyer_id)
    )

    return PairingJoinResponse(
        token=jwt_token,
        user_id=str(new_user.id),
        foyer_id=str(pt.foyer_id),
        foyer_name=foyer.name,
        kind=pt.kind,
        recovery_code=recovery_code,
        display_name=new_user.display_name,
    )
