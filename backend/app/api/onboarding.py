"""Endpoints onboarding — wizard "Creer un foyer" sans compte (v0.30).

Endpoint principal : POST /api/onboarding/create-foyer. Unauth (l'user
n'a pas encore de JWT a la 1re installation). Cree User + Foyer +
UserFoyer(admin) + recovery_code en une seule transaction, retourne
JWT + recovery_code (en clair, une seule fois).

Voir docs/design-onboarding-public.md §4 pour le wizard 5 etapes.

L'import du catalogue (rooms/tasks) reste sur les endpoints existants
de /api/catalog/import/apply — le client appelle 1) /onboarding/create-foyer,
recupere son JWT, puis 2) /catalog/import/apply avec ce JWT pour
materialiser les pieces et taches choisies dans le wizard.
"""

from __future__ import annotations

import secrets
import uuid

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import create_token
from app.database import get_session
from app.models.foyer import Foyer
from app.models.user import User
from app.models.user_foyer import UserFoyer
from app.utils.recovery_code import generate_code, hash_code

router = APIRouter(prefix="/api/onboarding", tags=["onboarding"])


class CreateFoyerRequest(BaseModel):
    display_name: str = Field(min_length=1, max_length=100)
    foyer_name: str = Field(min_length=1, max_length=100)
    device_id: str = Field(min_length=1, max_length=64)


class CreateFoyerResponse(BaseModel):
    token: str
    user_id: str
    foyer_id: str
    foyer_name: str
    recovery_code: str  # affiche au user UNE SEULE FOIS


@router.post("/create-foyer", response_model=CreateFoyerResponse, status_code=201)
async def create_foyer_bootstrap(
    body: CreateFoyerRequest,
    session: AsyncSession = Depends(get_session),
):
    """Bootstrap complet d'un nouveau foyer pour un user qui installe l'app
    pour la 1re fois.

    Cree atomiquement :
    - `User` (username auto-genere ; display_name fourni par le user)
    - `Foyer`
    - `UserFoyer(role=admin)`
    - `recovery_code` (hash stocke, clair retourne)

    Retourne un JWT pour les requetes suivantes (catalogue import,
    creation tasks, etc.).
    """
    auto_username = f"u_{secrets.token_hex(6)}"

    new_user = User(
        username=auto_username,
        display_name=body.display_name.strip(),
    )
    session.add(new_user)
    await session.flush()

    new_foyer = Foyer(name=body.foyer_name.strip())
    session.add(new_foyer)
    await session.flush()

    session.add(
        UserFoyer(user_id=new_user.id, foyer_id=new_foyer.id, role="admin")
    )

    new_user.last_active_foyer_id = new_foyer.id
    recovery_code = generate_code()
    new_user.recovery_code_hash = hash_code(recovery_code)

    await session.commit()
    await session.refresh(new_user)
    await session.refresh(new_foyer)

    jwt_token = create_token(
        str(new_user.id), body.device_id, foyer_id=str(new_foyer.id)
    )

    return CreateFoyerResponse(
        token=jwt_token,
        user_id=str(new_user.id),
        foyer_id=str(new_foyer.id),
        foyer_name=new_foyer.name,
        recovery_code=recovery_code,
    )
