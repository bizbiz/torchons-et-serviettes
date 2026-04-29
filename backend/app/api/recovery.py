"""Endpoints recovery — flow "Retrouver mon foyer" (v0.30).

3 endpoints :
- POST /recovery/regenerate : auth, genere un nouveau code et invalide
  l'ancien. Affiche en clair une seule fois.
- POST /recovery/restore : unauth, prend un code en clair, retourne
  l'identite candidate + un restore_token court-vie pour la verification
  step (Q5e du grill-me).
- POST /recovery/restore/confirm : unauth, consomme le restore_token et
  emet un vrai JWT.

Voir docs/design-onboarding-public.md §5 pour la decision de fond.

Coin technique : pas de lookup direct par hash (argon2id a un sel par hash).
La restauration itere sur tous les users avec un recovery_code_hash non
nul et appelle argon2.verify pour chacun. O(N) en nombre d'users — OK
jusqu'a quelques milliers (~50ms par verify), a optimiser plus tard avec
un prefix-hash deterministe si necessaire.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException
from jose import JWTError, jwt
from pydantic import BaseModel, Field
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import ALGORITHM, create_token, get_current_user
from app.config import settings
from app.database import get_session
from app.models.completion import TaskCompletion
from app.models.foyer import Foyer
from app.models.task_affinity import TaskAffinity
from app.models.user import User
from app.models.user_foyer import UserFoyer
from app.utils.recovery_code import generate_code, hash_code, verify_code

router = APIRouter(prefix="/api/recovery", tags=["recovery"])

# TTL du restore_token (entre /restore et /restore/confirm).
_RESTORE_TOKEN_TTL = timedelta(minutes=5)
_RESTORE_SCOPE = "restore"


class RegenerateResponse(BaseModel):
    recovery_code: str  # clair, affiche au user UNE SEULE FOIS


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
        raise HTTPException(
            status_code=401, detail="Token de restauration invalide ou expire"
        )
    if claims.get("scope") != _RESTORE_SCOPE:
        raise HTTPException(status_code=401, detail="Scope token incorrect")
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
        raise HTTPException(status_code=404, detail="Utilisateur introuvable")

    new_code = generate_code()
    user.recovery_code_hash = hash_code(new_code)
    user.recovery_code_last_used_at = None  # reset
    await session.commit()

    return RegenerateResponse(recovery_code=new_code)


@router.post("/restore", response_model=RestoreResponse)
async def restore(
    body: RestoreRequest,
    session: AsyncSession = Depends(get_session),
):
    """Etape 1 du flow "Retrouver mon foyer" : trouve le user qui possede le
    code, retourne un preview de son identite + un restore_token pour valider.

    Pas d'auth requise. Iteration O(N) sur les users (cf. note module).
    """
    # Recupere tous les users avec un hash actif (non supprimes)
    candidates = await session.execute(
        select(User).where(
            User.recovery_code_hash.is_not(None),
            User.deleted_at.is_(None),
        )
    )
    matched: User | None = None
    for u in candidates.scalars().all():
        if verify_code(body.recovery_code, u.recovery_code_hash):
            matched = u
            break

    if matched is None:
        raise HTTPException(
            status_code=404,
            detail="Code de recuperation introuvable",
        )

    # Resoud le foyer actif (si encore membre, sinon best-effort first foyer)
    foyer_name: str | None = None
    role: str | None = None
    foyer_id_for_stats: uuid.UUID | None = None
    if matched.last_active_foyer_id is not None:
        foyer_id_for_stats = matched.last_active_foyer_id
        foyer_q = await session.execute(
            select(Foyer.name, UserFoyer.role)
            .join(UserFoyer, UserFoyer.foyer_id == Foyer.id)
            .where(
                Foyer.id == matched.last_active_foyer_id,
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


@router.post("/restore/confirm", response_model=RestoreConfirmResponse)
async def restore_confirm(
    body: RestoreConfirmRequest,
    session: AsyncSession = Depends(get_session),
):
    """Etape 2 du flow restore : l'user a confirme son identite cote app, on
    emet le vrai JWT. Met a jour `recovery_code_last_used_at` pour audit.
    Reactive aussi la membership soft-leaved si applicable (cf. Q9 grill-me).
    """
    user_id_str = _decode_restore_token(body.restore_token)
    user_id = uuid.UUID(user_id_str)

    user_result = await session.execute(select(User).where(User.id == user_id))
    user = user_result.scalar_one_or_none()
    if user is None or user.deleted_at is not None:
        raise HTTPException(status_code=404, detail="Utilisateur introuvable")

    user.recovery_code_last_used_at = datetime.now(timezone.utc)

    # Si l'user avait quitte son dernier foyer, on reactive sa membership
    # (cf. Q9e du grill-me : recovery code => restauration de l'identite
    # complete, y compris la membership).
    foyer_id: uuid.UUID | None = user.last_active_foyer_id
    if foyer_id is not None:
        membership_result = await session.execute(
            select(UserFoyer).where(
                UserFoyer.user_id == user_id,
                UserFoyer.foyer_id == foyer_id,
            )
        )
        uf = membership_result.scalar_one_or_none()
        if uf is not None and uf.left_at is not None:
            uf.left_at = None
            if uf.role == "former_member":
                uf.role = "member"

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
