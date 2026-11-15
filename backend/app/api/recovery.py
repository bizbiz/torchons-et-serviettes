"""Endpoints recovery — flow "Retrouver mon foyer" (v0.30).

3 endpoints :
- POST /recovery/regenerate : auth, genere un nouveau code et invalide
  l'ancien. Affiche en clair une seule fois.
- POST /recovery/restore : unauth, prend un code en clair, retourne
  l'identite candidate + un restore_token court-vie pour la verification
  step (Q5e du design review).
- POST /recovery/restore/confirm : unauth, consomme le restore_token et
  emet un vrai JWT.

Coin technique : pas de lookup direct par hash (argon2id a un sel par hash).
La restauration itere sur tous les users avec un recovery_code_hash non
nul et appelle argon2.verify pour chacun. O(N) en nombre d'users — OK
jusqu'a quelques milliers (~50ms par verify), a optimiser plus tard avec
un prefix-hash deterministe si necessaire.
"""

from __future__ import annotations

import asyncio
import uuid
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException
from jose import JWTError, jwt
from pydantic import BaseModel, Field
from sqlalchemy import func, or_, select
from sqlalchemy.ext.asyncio import AsyncSession
from app.utils.rate_limit import rate_limited

from app.auth import ALGORITHM, create_token, get_current_user
from app.api.devices import revoke_other_devices
from app.config import settings
from app.database import get_session
from app.models.completion import TaskCompletion
from app.models.foyer import Foyer
from app.models.task_affinity import TaskAffinity
from app.models.user import User
from app.models.user_foyer import UserFoyer
from app.services.member_departure import (
    bring_back,
    can_still_return,
    foyer_to_return_to,
    return_cutoff,
)
from app.utils.errors import LocalizedHTTPException
from app.utils.recovery_code import (
    generate_code, hash_code, lookup_key, lookup_key_id, verify_code,
)

router = APIRouter(prefix="/api/recovery", tags=["recovery"])

# TTL du restore_token (entre /restore et /restore/confirm).
_RESTORE_TOKEN_TTL = timedelta(minutes=5)
_RESTORE_SCOPE = "restore"


class RegenerateResponse(BaseModel):
    recovery_code: str  # clair, affiche au user UNE SEULE FOIS
    # Nombre d'autres appareils coupes par cette regeneration.
    revoked_devices: int = 0


class RestoreRequest(BaseModel):
    recovery_code: str = Field(min_length=4, max_length=20)


class RestoreResponse(BaseModel):
    """Etape 1 du restore : confirmation visuelle de l'identite avant emission
    du JWT. Permet a l'user de verifier qu'il restaure le bon foyer."""
    display_name: str
    foyer_name: str | None  # null si le user n'a aucun foyer actif
    role: str | None  # admin/member/former_member, null si pas de foyer
    ratings_count: int
    completions_count: int
    restore_token: str


class RestoreConfirmRequest(BaseModel):
    restore_token: str
    device_id: str = Field(min_length=1, max_length=64)


class RestoreConfirmResponse(BaseModel):
    token: str
    user_id: str
    foyer_id: str | None


def _create_restore_token(user_id: str) -> str:
    """JWT court-vie scoped 'restore' utilise entre /restore et /confirm."""
    expires = datetime.now(timezone.utc) + _RESTORE_TOKEN_TTL
    return jwt.encode(
        {"sub": user_id, "scope": _RESTORE_SCOPE, "exp": expires},
        settings.jwt_secret,
        algorithm=ALGORITHM,
    )


def _decode_restore_token(token: str) -> str:
    """Retourne user_id si valide. Leve HTTPException sinon."""
    try:
        claims = jwt.decode(token, settings.jwt_secret, algorithms=[ALGORITHM])
    except JWTError:
        raise LocalizedHTTPException(
            status_code=401, error_code="RECOVERY_TOKEN_INVALID"
        )
    if claims.get("scope") != _RESTORE_SCOPE:
        raise LocalizedHTTPException(status_code=401, error_code="RECOVERY_TOKEN_INVALID")
    return claims["sub"]


@router.post("/regenerate", response_model=RegenerateResponse)
async def regenerate_recovery_code(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Genere un nouveau recovery_code pour l'user courant. L'ancien hash est
    ecrase, donc l'ancien code ne marche plus. Le code clair est affiche une
    seule fois au user (a noter immediatement).
    """
    user_id = uuid.UUID(current["user_id"])
    user_result = await session.execute(select(User).where(User.id == user_id))
    user = user_result.scalar_one_or_none()
    if user is None or user.deleted_at is not None:
        raise LocalizedHTTPException(status_code=404, error_code="USER_NOT_FOUND")

    new_code = generate_code()
    user.recovery_code_hash = hash_code(new_code)
    user.recovery_lookup = lookup_key(new_code)
    user.recovery_code_last_used_at = None  # reset

    # Un code regenere signifie « l'ancien a pu fuiter ». Fermer la porte du
    # code sans fermer celles deja ouvertes ne protege de rien : quelqu'un
    # qui a deja restaure detient un jeton, et ce jeton n'expire pas. On
    # revoque donc tous les AUTRES appareils dans le meme geste — celui qui
    # appelle reste connecte, sinon l'utilisateur se met dehors en cliquant.
    revoked = await revoke_other_devices(
        session, current["user_id"], current["device_id"]
    )
    await session.commit()

    return RegenerateResponse(recovery_code=new_code, revoked_devices=revoked)


def _match_code(code: str, users: list[User]) -> User | None:
    """First user whose stored hash matches `code` (argon2, one verify per user)."""
    for u in users:
        if verify_code(code, u.recovery_code_hash):
            return u
    return None


@router.post(
    "/restore",
    response_model=RestoreResponse,
    # A wrong code costs one argon2 verify per account holding a code (61 ms each,
    # 16 accounts in production on 2026-10-12): about one second per attempt.
    dependencies=[Depends(rate_limited("recovery_restore", max_calls=5, per_seconds=60))],
)
async def restore(
    body: RestoreRequest,
    session: AsyncSession = Depends(get_session),
):
    """Etape 1 du flow "Retrouver mon foyer" : trouve le user qui possede le
    code, retourne un preview de son identite + un restore_token pour valider.

    Pas d'auth requise.

    Lookup by `recovery_lookup` first (one argon2 verify). Only accounts with no
    key made with the current secret -- codes issued before 2026-10-12, or keyed
    before a JWT_SECRET rotation -- are scanned one by one, and the matching one
    is keyed on the spot: that set only shrinks. A wrong code costs the scan of
    that set, no longer of every account.
    """
    key = lookup_key(body.recovery_code)
    # A deleted account can still come back during its return window (member_departure.py);
    # an anonymized one has no code left.
    alive = (
        User.recovery_code_hash.is_not(None),
        or_(User.deleted_at.is_(None), User.deleted_at >= return_cutoff()),
    )

    keyed = await session.execute(select(User).where(*alive, User.recovery_lookup == key))
    # argon2 is CPU-bound and synchronous: verify in a thread, the event loop of
    # the single worker would otherwise freeze.
    matched: User | None = await asyncio.to_thread(
        _match_code, body.recovery_code, list(keyed.scalars().all())
    )
    if matched is None:
        unkeyed = await session.execute(select(User).where(
            *alive,
            or_(
                User.recovery_lookup.is_(None),
                User.recovery_lookup.not_like(f"{lookup_key_id()}:%"),
            ),
        ))
        matched = await asyncio.to_thread(
            _match_code, body.recovery_code, list(unkeyed.scalars().all())
        )
        if matched is not None:
            matched.recovery_lookup = key
            await session.commit()

    if matched is None:
        raise LocalizedHTTPException(
            status_code=404,
            error_code="RECOVERY_CODE_NOT_FOUND",
        )

    # Resoud le foyer actif (si encore membre, sinon best-effort first foyer)
    foyer_name: str | None = None
    role: str | None = None
    foyer_id_for_stats = await foyer_to_return_to(session, matched)
    if foyer_id_for_stats is not None:
        foyer_q = await session.execute(
            # left_at deliberately NOT filtered: restore_confirm reactivates this membership (design Q9e).
            select(Foyer.name, UserFoyer.role)
            .join(UserFoyer, UserFoyer.foyer_id == Foyer.id)
            .where(
                Foyer.id == foyer_id_for_stats,
                UserFoyer.user_id == matched.id,
                Foyer.deleted_at.is_(None),
            )
        )
        row = foyer_q.first()
        if row is not None:
            foyer_name, role = row

    # Compteurs pour la verification visuelle (Q5e)
    ratings_count = (
        await session.execute(
            select(func.count(TaskAffinity.task_id)).where(
                TaskAffinity.user_id == matched.id
            )
        )
    ).scalar_one()
    completions_count = (
        await session.execute(
            select(func.count(TaskCompletion.id)).where(
                TaskCompletion.completed_by == matched.id
            )
        )
    ).scalar_one()

    return RestoreResponse(
        display_name=matched.display_name,
        foyer_name=foyer_name,
        role=role,
        ratings_count=ratings_count or 0,
        completions_count=completions_count or 0,
        restore_token=_create_restore_token(str(matched.id)),
    )


@router.post(
    "/restore/confirm",
    response_model=RestoreConfirmResponse,
    dependencies=[Depends(rate_limited("recovery_confirm", max_calls=10, per_seconds=60))],
)
async def restore_confirm(
    body: RestoreConfirmRequest,
    session: AsyncSession = Depends(get_session),
):
    """Etape 2 du flow restore : l'user a confirme son identite cote app, on
    emet le vrai JWT. Met a jour `recovery_code_last_used_at` pour audit.
    Reactive aussi la membership soft-leaved si applicable (cf. Q9 design review).
    """
    user_id_str = _decode_restore_token(body.restore_token)
    user_id = uuid.UUID(user_id_str)

    user_result = await session.execute(select(User).where(User.id == user_id))
    user = user_result.scalar_one_or_none()
    if user is None or not can_still_return(user):
        raise LocalizedHTTPException(status_code=404, error_code="USER_NOT_FOUND")

    user.recovery_code_last_used_at = datetime.now(timezone.utc)

    # The recovery code restores the whole identity, membership included (design Q9e). Since
    # 2026-11-02 this also undoes a departure or a deletion still in its 30-day window: the
    # household left last, the suspended solo tasks (services/member_departure.py).
    foyer_id = await bring_back(session, user)

    await session.commit()

    jwt_token = create_token(
        str(user.id),
        body.device_id,
        foyer_id=str(foyer_id) if foyer_id else None,
    )
    return RestoreConfirmResponse(
        token=jwt_token,
        user_id=str(user.id),
        foyer_id=str(foyer_id) if foyer_id else None,
    )
