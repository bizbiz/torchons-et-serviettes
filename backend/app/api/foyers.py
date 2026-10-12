"""Routes CRUD foyers + endpoint de switch (regenere le JWT)."""

from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import create_token, get_current_user
from app.database import get_session
from app.models.foyer import Foyer
from app.models.user import User
from app.models.user_foyer import UserFoyer
from app.schemas.foyer import FoyerCreate, FoyerResponse, FoyerUpdate
from app.services.lifecycle import soft_leave_foyer
from app.services.presence import seed_initial_presence
from app.utils.changelog import record_change
from app.utils.errors import LocalizedHTTPException
from app.utils.scoping import current_foyer_uuid

router = APIRouter(prefix="/api/foyers", tags=["foyers"])


@router.get("", response_model=list[FoyerResponse])
async def list_my_foyers(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Liste les foyers auxquels l'user courant appartient."""
    user_id = uuid.UUID(current["user_id"])
    current_foyer_id = uuid.UUID(current["foyer_id"])

    result = await session.execute(
        select(Foyer, UserFoyer.role)
        .join(UserFoyer, UserFoyer.foyer_id == Foyer.id)
        .where(
            UserFoyer.user_id == user_id,
            Foyer.deleted_at.is_(None),
        )
        .order_by(Foyer.name)
    )
    rows = result.all()
    return [
        FoyerResponse(
            id=f.id,
            name=f.name,
            home_freeze_enabled=f.home_freeze_enabled,
            created_at=f.created_at,
            role=role,
            is_current=(f.id == current_foyer_id),
            is_premium=f.is_premium,
            premium_until=f.premium_until,
            gamification_enabled=f.gamification_enabled,
        )
        for f, role in rows
    ]


@router.post("", response_model=FoyerResponse, status_code=201)
async def create_foyer(
    body: FoyerCreate,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Cree un nouveau foyer et y ajoute le user courant comme admin.

    Le foyer cree devient automatiquement le foyer actif (last_active_foyer_id
    est mis a jour). Le client doit ensuite appeler /switch pour recuperer
    un nouveau JWT avec le foyer actif.
    """
    user_id = uuid.UUID(current["user_id"])

    foyer = Foyer(name=body.name)
    session.add(foyer)
    await session.flush()

    # Presence confirmee par defaut sur l'annee ecoulee (cf
    # seed_initial_presence : evite le gel Home-freeze sur foyer neuf).
    await seed_initial_presence(session, foyer.id)

    session.add(UserFoyer(user_id=user_id, foyer_id=foyer.id, role="admin"))
    await session.commit()
    await session.refresh(foyer)

    return FoyerResponse(
        id=foyer.id,
        name=foyer.name,
        home_freeze_enabled=foyer.home_freeze_enabled,
        created_at=foyer.created_at,
        role="admin",
        is_current=False,  # pas encore switche dessus
        is_premium=foyer.is_premium,
        premium_until=foyer.premium_until,
        gamification_enabled=foyer.gamification_enabled,
    )


@router.put("/{foyer_id}", response_model=FoyerResponse)
async def update_foyer(
    foyer_id: uuid.UUID,
    body: FoyerUpdate,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Met a jour les reglages d'un foyer. Uniquement accessible aux admins
    du foyer (verification du role via user_foyers).
    """
    user_id = uuid.UUID(current["user_id"])

    # Verifie que l'user est admin du foyer
    membership = await session.execute(
        select(UserFoyer).where(
            UserFoyer.user_id == user_id,
            UserFoyer.foyer_id == foyer_id,
        )
    )
    uf = membership.scalar_one_or_none()
    if uf is None or uf.role != "admin":
        raise LocalizedHTTPException(
            status_code=403,
            error_code="FOYER_ADMIN_REQUIRED",
        )

    foyer_result = await session.execute(select(Foyer).where(Foyer.id == foyer_id))
    foyer = foyer_result.scalar_one_or_none()
    if foyer is None:
        raise LocalizedHTTPException(status_code=404, error_code="FOYER_NOT_FOUND")

    for key, val in body.model_dump(exclude_unset=True).items():
        setattr(foyer, key, val)
    foyer.version += 1
    await session.commit()
    await session.refresh(foyer)

    return FoyerResponse(
        id=foyer.id,
        name=foyer.name,
        home_freeze_enabled=foyer.home_freeze_enabled,
        created_at=foyer.created_at,
        role=uf.role,
        is_current=(foyer_id == current_foyer_uuid(current)),
        is_premium=foyer.is_premium,
        premium_until=foyer.premium_until,
        gamification_enabled=foyer.gamification_enabled,
    )


class GamificationToggle(BaseModel):
    """Payload pour PUT /api/foyers/{foyer_id}/gamification (v0.32)."""

    enabled: bool


@router.put("/{foyer_id}/gamification", response_model=FoyerResponse)
async def update_foyer_gamification(
    foyer_id: uuid.UUID,
    body: GamificationToggle,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Toggle gamification du foyer — accessible a tout membre (pas admin-only).

    Cf design review Q4 : la decision de couper la gamification est collective au
    sens "tous les membres sont affectes" mais peut etre prise par n'importe
    quel membre. La friction est cote UI (dialog de confirmation mentionnant
    l'impact sur l'autre).
    """
    user_id = uuid.UUID(current["user_id"])

    membership = await session.execute(
        select(UserFoyer).where(
            UserFoyer.user_id == user_id,
            UserFoyer.foyer_id == foyer_id,
            UserFoyer.left_at.is_(None),
        )
    )
    uf = membership.scalar_one_or_none()
    if uf is None:
        raise LocalizedHTTPException(
            status_code=403,
            error_code="FOYER_NOT_MEMBER",
        )

    foyer_result = await session.execute(select(Foyer).where(Foyer.id == foyer_id))
    foyer = foyer_result.scalar_one_or_none()
    if foyer is None:
        raise LocalizedHTTPException(status_code=404, error_code="FOYER_NOT_FOUND")

    foyer.gamification_enabled = body.enabled
    foyer.version += 1
    await record_change(
        session, "foyers", foyer.id, "UPDATE", current["user_id"],
        foyer_id=str(foyer.id),
        new_data={"gamification_enabled": body.enabled},
    )
    await session.commit()
    await session.refresh(foyer)

    return FoyerResponse(
        id=foyer.id,
        name=foyer.name,
        home_freeze_enabled=foyer.home_freeze_enabled,
        created_at=foyer.created_at,
        role=uf.role,
        is_current=(foyer_id == current_foyer_uuid(current)),
        is_premium=foyer.is_premium,
        premium_until=foyer.premium_until,
        gamification_enabled=foyer.gamification_enabled,
    )


# Endpoint GET /home-freeze-status supprime : le systeme de presence v2
# n'utilise plus les champs wifi_optin / is_currently_at_home / last_seen_at_home_at.
# Le toggle home_freeze_enabled est suffisant (dans FoyerResponse).


@router.post("/{foyer_id}/switch")
async def switch_foyer(
    foyer_id: uuid.UUID,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Switch le foyer actif de l'user courant. Regenere un JWT avec le nouveau
    foyer_id et met a jour users.last_active_foyer_id.

    Le client doit remplacer son token par celui renvoye et vider son cache
    local (le contenu du nouveau foyer est different).
    """
    user_id = uuid.UUID(current["user_id"])

    # Verifie que le user est bien membre ACTIF du foyer demande : sans
    # left_at, un ancien membre rebasculait sur le foyer qu'il avait quitte.
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
            error_code="FOYER_NOT_MEMBER",
        )

    # Met a jour last_active_foyer_id
    user_result = await session.execute(select(User).where(User.id == user_id))
    user = user_result.scalar_one_or_none()
    if user is None:
        raise LocalizedHTTPException(status_code=404, error_code="USER_NOT_FOUND")
    user.last_active_foyer_id = foyer_id
    await session.commit()

    # Regenere un JWT avec le nouveau foyer actif
    token = create_token(
        str(user_id),
        current["device_id"],
        foyer_id=str(foyer_id),
    )
    return {
        "token": token,
        "foyer_id": str(foyer_id),
    }


@router.post("/{foyer_id}/leave")
async def leave_foyer(
    foyer_id: uuid.UUID,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Self-leave : l'user courant quitte ce foyer (v0.30).

    Soft-leave : `user_foyers.left_at = now()` + role -> 'former_member'.
    Si l'user etait le dernier admin, auto-promote du plus ancien member
    restant (cf. Q9 du design review, services/lifecycle.py).

    Si le foyer quitte etait le foyer actif de l'user, son
    `last_active_foyer_id` bascule sur le 1er foyer encore actif qu'il a,
    ou null s'il n'en a plus. Un nouveau JWT est emis dans ce cas.
    """
    user_id = uuid.UUID(current["user_id"])

    actually_left = await soft_leave_foyer(session, user_id, foyer_id)
    if not actually_left:
        raise LocalizedHTTPException(
            status_code=404,
            error_code="FOYER_NOT_ACTIVE_MEMBER",
        )

    # Si c'etait le foyer actif, bascule last_active sur un autre foyer ou null
    user_result = await session.execute(select(User).where(User.id == user_id))
    user = user_result.scalar_one_or_none()
    new_token: str | None = None
    new_foyer_id: str | None = None
    if user is not None and user.last_active_foyer_id == foyer_id:
        next_foyer_result = await session.execute(
            select(UserFoyer.foyer_id)
            .where(
                UserFoyer.user_id == user_id,
                UserFoyer.left_at.is_(None),
            )
            .order_by(UserFoyer.joined_at)
            .limit(1)
        )
        next_foyer = next_foyer_result.scalar_one_or_none()
        user.last_active_foyer_id = next_foyer
        if next_foyer is not None:
            new_foyer_id = str(next_foyer)
            new_token = create_token(
                str(user_id), current["device_id"], foyer_id=new_foyer_id
            )

    await session.commit()

    return {
        "left_foyer_id": str(foyer_id),
        "new_active_foyer_id": new_foyer_id,
        "new_token": new_token,
    }
