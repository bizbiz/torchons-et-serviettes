"""Routes CRUD foyers + endpoint de switch (regenere le JWT)."""

from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import create_token, get_current_user
from app.database import get_session
from app.models.foyer import Foyer
from app.models.user import User
from app.models.user_foyer import UserFoyer
from app.schemas.foyer import FoyerCreate, FoyerResponse, FoyerUpdate
from app.utils.changelog import record_change
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
        raise HTTPException(
            status_code=403,
            detail="Seul un admin du foyer peut modifier ses reglages",
        )

    foyer_result = await session.execute(select(Foyer).where(Foyer.id == foyer_id))
    foyer = foyer_result.scalar_one_or_none()
    if foyer is None:
        raise HTTPException(status_code=404, detail="Foyer introuvable")

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

    # Verifie que le user est bien membre du foyer demande
    membership = await session.execute(
        select(UserFoyer).where(
            UserFoyer.user_id == user_id,
            UserFoyer.foyer_id == foyer_id,
        )
    )
    if membership.scalar_one_or_none() is None:
        raise HTTPException(
            status_code=403,
            detail="Tu n'es pas membre de ce foyer",
        )

    # Met a jour last_active_foyer_id
    user_result = await session.execute(select(User).where(User.id == user_id))
    user = user_result.scalar_one_or_none()
    if user is None:
        raise HTTPException(status_code=404, detail="User introuvable")
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
