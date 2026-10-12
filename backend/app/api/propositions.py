"""Routes propositions d'objectifs — Feature 3 v0.8.1 (magic buttons).

Flow live :
1. Proposer POST /api/objective-propositions avec les sliders multi-user
   -> creation de la proposition (status=pending) + FCM data aux autres
2. Chaque autre user POST /api/objective-propositions/{id}/respond
   {accepted: true|false}
   -> ajoute une row dans objective_proposition_responses + FCM au proposer
3. Si tous accepted -> persist objectifs sur user_foyers + status=accepted
   + FCM celebration a tous
4. Si un declined -> status=declined, pas de persist, FCM "declined" a tous
5. Si timeout 3min depuis created_at -> status=expired au premier read apres

Fail-soft : le proposer ne peut pas avoir plusieurs propositions pending en
meme temps dans le meme foyer. Si une existe deja et n'est pas expiree, on
la re-marque comme expired et on en cree une nouvelle.

GET /api/objective-propositions/pending : poll de fallback si le FCM push
n'est pas recu (reseau foireux, notif service down). Retourne la proposition
active adressee a l'user courant, ou 404 si rien.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone
from typing import Any

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field
from sqlalchemy import and_, or_, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.utils.errors import LocalizedHTTPException
from app.models.objective_proposition import (
    ObjectiveProposition,
    ObjectivePropositionResponse,
)
from app.models.user import User
from app.models.user_foyer import UserFoyer
from app.services.push import send_data_message_to_users
from app.utils.scoping import current_foyer_uuid

router = APIRouter(prefix="/api/objective-propositions", tags=["propositions"])


PROPOSITION_TIMEOUT_MINUTES = 3


# --- Schemas ---


class PropositionCreate(BaseModel):
    """Corps POST /api/objective-propositions.

    slider_values : {user_id_str: minutes} pour tous les users du foyer
    (y compris le proposer).
    """
    slider_values: dict[str, int] = Field(
        description="map user_id -> minutes/jour",
    )


class PropositionResponseBody(BaseModel):
    accepted: bool


class UserResponseInfo(BaseModel):
    user_id: uuid.UUID
    display_name: str
    status: str  # 'pending' / 'accepted' / 'declined'
    responded_at: datetime | None = None


class PropositionView(BaseModel):
    id: uuid.UUID
    foyer_id: uuid.UUID
    proposer_id: uuid.UUID
    proposer_name: str
    slider_values: dict[str, int]
    status: str
    created_at: datetime
    expires_at: datetime
    responses: list[UserResponseInfo]


# --- Helpers ---


def _is_expired(prop: ObjectiveProposition) -> bool:
    """True si la proposition a depasse son expires_at."""
    now = datetime.now(timezone.utc)
    expires = prop.expires_at
    if expires.tzinfo is None:
        expires = expires.replace(tzinfo=timezone.utc)
    return now > expires


async def _expire_if_needed(
    session: AsyncSession, prop: ObjectiveProposition
) -> bool:
    """Si la proposition est pending et expiree, la passe en expired.
    Retourne True si on l'a marquee expired.
    """
    if prop.status != "pending":
        return False
    if not _is_expired(prop):
        return False
    prop.status = "expired"
    await session.commit()
    return True


async def _build_proposition_view(
    session: AsyncSession, prop: ObjectiveProposition
) -> PropositionView:
    """Charge les users du foyer + leur statut de reponse pour construire la vue."""
    # Membres ACTIFS du foyer : un ancien membre ne repondra jamais, le compter
    # bloquait la proposition (les quatre requetes ci-dessous filtrent left_at).
    users_result = await session.execute(
        select(User)
        .join(UserFoyer, UserFoyer.user_id == User.id)
        .where(UserFoyer.foyer_id == prop.foyer_id, UserFoyer.left_at.is_(None))
    )
    users = {u.id: u for u in users_result.scalars().all()}

    # Reponses existantes
    resp_result = await session.execute(
        select(ObjectivePropositionResponse).where(
            ObjectivePropositionResponse.proposition_id == prop.id
        )
    )
    responses = {r.user_id: r for r in resp_result.scalars().all()}

    # Le proposer est auto-accepted (il a vu et soumis lui-meme)
    responses_view: list[UserResponseInfo] = []
    for user_id, user in users.items():
        if user_id == prop.proposer_id:
            status = "accepted"
            responded_at = prop.created_at
        elif user_id in responses:
            r = responses[user_id]
            status = "accepted" if r.accepted else "declined"
            responded_at = r.responded_at
        else:
            status = "pending"
            responded_at = None
        responses_view.append(
            UserResponseInfo(
                user_id=user_id,
                display_name=user.display_name,
                status=status,
                responded_at=responded_at,
            )
        )

    return PropositionView(
        id=prop.id,
        foyer_id=prop.foyer_id,
        proposer_id=prop.proposer_id,
        proposer_name=users[prop.proposer_id].display_name if prop.proposer_id in users else "?",
        slider_values={str(k): int(v) for k, v in prop.slider_values_json.items()},
        status=prop.status,
        created_at=prop.created_at,
        expires_at=prop.expires_at,
        responses=responses_view,
    )


async def _persist_accepted_objectives(
    session: AsyncSession, prop: ObjectiveProposition
) -> None:
    """Copie les slider_values sur user_foyers.daily_objective_minutes."""
    for user_id_str, minutes in prop.slider_values_json.items():
        await session.execute(
            update(UserFoyer)
            .where(
                UserFoyer.user_id == uuid.UUID(user_id_str),
                UserFoyer.foyer_id == prop.foyer_id,
            )
            .values(daily_objective_minutes=int(minutes))
        )


# --- Endpoints ---


@router.post("", response_model=PropositionView, status_code=201)
async def create_proposition(
    body: PropositionCreate,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Cree une nouvelle proposition multi-user pour le foyer actif.

    Cote Android : appele quand le proposer clique "Proposer au foyer"
    dans le popup multi-slider. Les autres users recoivent un FCM data
    message qui ouvre chez eux le modal accept/decline.
    """
    user_id = uuid.UUID(current["user_id"])
    foyer_uuid = current_foyer_uuid(current)

    # Check : l'user est bien membre du foyer
    membership = await session.execute(
        select(UserFoyer).where(
            UserFoyer.user_id == user_id,
            UserFoyer.foyer_id == foyer_uuid,
            UserFoyer.left_at.is_(None),
        )
    )
    if membership.scalar_one_or_none() is None:
        raise LocalizedHTTPException(status_code=403, error_code="FOYER_NOT_MEMBER")

    # Expire les anciennes propositions pending du foyer (une seule active a la fois)
    old_result = await session.execute(
        select(ObjectiveProposition).where(
            ObjectiveProposition.foyer_id == foyer_uuid,
            ObjectiveProposition.status == "pending",
        )
    )
    for old in old_result.scalars().all():
        old.status = "expired"

    # Validation des slider_values : tous les membres ACTIFS du foyer doivent
    # etre couverts (sans left_at, un ancien membre etait exige lui aussi).
    users_result = await session.execute(
        select(User.id)
        .join(UserFoyer, UserFoyer.user_id == User.id)
        .where(UserFoyer.foyer_id == foyer_uuid, UserFoyer.left_at.is_(None))
    )
    foyer_user_ids = {str(uid) for uid in users_result.scalars().all()}
    body_user_ids = set(body.slider_values.keys())
    if not foyer_user_ids.issubset(body_user_ids):
        missing = foyer_user_ids - body_user_ids
        raise LocalizedHTTPException(
            status_code=400,
            error_code="PROPOSITION_MISSING_SLIDERS_FORMAT",
            format_args={"missing": missing},
        )

    now = datetime.now(timezone.utc)
    prop = ObjectiveProposition(
        id=uuid.uuid4(),
        foyer_id=foyer_uuid,
        proposer_id=user_id,
        slider_values_json={k: int(v) for k, v in body.slider_values.items() if k in foyer_user_ids},
        status="pending",
        expires_at=now + timedelta(minutes=PROPOSITION_TIMEOUT_MINUTES),
    )
    session.add(prop)
    await session.commit()
    await session.refresh(prop)

    # FCM data aux autres users du foyer (pas le proposer lui-meme)
    other_user_ids = [
        uuid.UUID(uid) for uid in foyer_user_ids if uid != str(user_id)
    ]
    await send_data_message_to_users(
        session,
        other_user_ids,
        data={
            "type": "objective_proposition_created",
            "proposition_id": str(prop.id),
        },
    )

    return await _build_proposition_view(session, prop)


@router.post("/{proposition_id}/respond", response_model=PropositionView)
async def respond_to_proposition(
    proposition_id: uuid.UUID,
    body: PropositionResponseBody,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Accepter ou refuser une proposition. Cote Android : appele par les
    receveurs dans leur modal.

    Si accepted : on ajoute une row response, et si tous les users du foyer
    ont accepted (ou sont le proposer lui-meme), on persiste les objectifs
    sur user_foyers.daily_objective_minutes et on passe la proposition en
    status=accepted. FCM celebration a tous.

    Si declined : la proposition passe immediatement en status=declined,
    pas de persist. FCM notify a tous.
    """
    user_id = uuid.UUID(current["user_id"])

    result = await session.execute(
        select(ObjectiveProposition).where(ObjectiveProposition.id == proposition_id)
    )
    prop = result.scalar_one_or_none()
    if prop is None:
        raise LocalizedHTTPException(status_code=404, error_code="PROPOSITION_NOT_FOUND")

    # Check : user est membre du foyer concerne
    membership = await session.execute(
        select(UserFoyer).where(
            UserFoyer.user_id == user_id,
            UserFoyer.foyer_id == prop.foyer_id,
        )
    )
    if membership.scalar_one_or_none() is None:
        raise LocalizedHTTPException(status_code=403, error_code="FOYER_NOT_MEMBER")

    # Check : proposition toujours active (pending non expiree)
    if await _expire_if_needed(session, prop):
        # FCM notify all que c'est expired
        users_result = await session.execute(
            select(User.id)
            .join(UserFoyer, UserFoyer.user_id == User.id)
            .where(UserFoyer.foyer_id == prop.foyer_id, UserFoyer.left_at.is_(None))
        )
        await send_data_message_to_users(
            session,
            list(users_result.scalars().all()),
            data={
                "type": "objective_proposition_expired",
                "proposition_id": str(prop.id),
            },
        )
        raise LocalizedHTTPException(status_code=410, error_code="PROPOSITION_EXPIRED")
    if prop.status != "pending":
        raise LocalizedHTTPException(
            status_code=409,
            error_code="PROPOSITION_ALREADY_FORMAT",
            format_args={"status": prop.status},
        )

    # Le proposer ne peut pas respondre a sa propre proposition (auto-accepted)
    if user_id == prop.proposer_id:
        raise LocalizedHTTPException(status_code=400, error_code="PROPOSITION_IS_PROPOSER")

    # Store la reponse (unique par user_id - on a la PK composite)
    existing_result = await session.execute(
        select(ObjectivePropositionResponse).where(
            ObjectivePropositionResponse.proposition_id == prop.id,
            ObjectivePropositionResponse.user_id == user_id,
        )
    )
    existing = existing_result.scalar_one_or_none()
    if existing is not None:
        raise LocalizedHTTPException(status_code=409, error_code="PROPOSITION_ALREADY_RESPONDED")

    session.add(
        ObjectivePropositionResponse(
            proposition_id=prop.id,
            user_id=user_id,
            accepted=body.accepted,
        )
    )

    # Charge tous les users du foyer pour decider le flow
    foyer_users_result = await session.execute(
        select(User.id)
        .join(UserFoyer, UserFoyer.user_id == User.id)
        .where(UserFoyer.foyer_id == prop.foyer_id, UserFoyer.left_at.is_(None))
    )
    foyer_user_ids = [row for row in foyer_users_result.scalars().all()]

    if not body.accepted:
        # Decline immediat -> status=declined
        prop.status = "declined"
        await session.commit()
        await session.refresh(prop)
        await send_data_message_to_users(
            session,
            foyer_user_ids,
            data={
                "type": "objective_proposition_declined",
                "proposition_id": str(prop.id),
                "declined_by": str(user_id),
            },
        )
        return await _build_proposition_view(session, prop)

    # Accepted : check si tous les users du foyer ont accepted (ou sont proposer)
    resp_result = await session.execute(
        select(ObjectivePropositionResponse).where(
            ObjectivePropositionResponse.proposition_id == prop.id,
            ObjectivePropositionResponse.accepted.is_(True),
        )
    )
    accepted_user_ids = {r.user_id for r in resp_result.scalars().all()}
    accepted_user_ids.add(prop.proposer_id)

    need_more = set(foyer_user_ids) - accepted_user_ids
    if need_more:
        # Il manque encore des acceptations : FCM progress update au proposer
        await session.commit()
        await session.refresh(prop)
        await send_data_message_to_users(
            session,
            [prop.proposer_id],
            data={
                "type": "objective_proposition_progress",
                "proposition_id": str(prop.id),
                "accepted_by": str(user_id),
            },
        )
        return await _build_proposition_view(session, prop)

    # Tous accepted ! Persist + celebration
    await _persist_accepted_objectives(session, prop)
    prop.status = "accepted"
    await session.commit()
    await session.refresh(prop)

    await send_data_message_to_users(
        session,
        foyer_user_ids,
        data={
            "type": "objective_proposition_accepted_all",
            "proposition_id": str(prop.id),
        },
    )
    return await _build_proposition_view(session, prop)


@router.get("/pending", response_model=PropositionView | None)
async def get_pending_proposition(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Proposition active adressee a l'user courant dans le foyer actif.

    Fallback polling au cas ou le FCM push n'est pas arrive. Retourne null
    si rien n'est pending (ou si l'user a deja repondu a celle en cours).
    """
    user_id = uuid.UUID(current["user_id"])
    foyer_uuid = current_foyer_uuid(current)

    result = await session.execute(
        select(ObjectiveProposition).where(
            ObjectiveProposition.foyer_id == foyer_uuid,
            ObjectiveProposition.status == "pending",
        ).order_by(ObjectiveProposition.created_at.desc())
    )
    prop = result.scalar_one_or_none()
    if prop is None:
        return None

    # Expire-on-read
    if await _expire_if_needed(session, prop):
        return None

    # Si l'user a deja repondu, on ne le surface pas comme "pending" pour lui
    if user_id == prop.proposer_id:
        return await _build_proposition_view(session, prop)
    existing_result = await session.execute(
        select(ObjectivePropositionResponse).where(
            ObjectivePropositionResponse.proposition_id == prop.id,
            ObjectivePropositionResponse.user_id == user_id,
        )
    )
    if existing_result.scalar_one_or_none() is not None:
        return None

    return await _build_proposition_view(session, prop)


@router.delete("/{proposition_id}")
async def cancel_proposition(
    proposition_id: uuid.UUID,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Annule une proposition en cours. Reserve au proposer lui-meme
    (pas d'annulation par un autre user). Passe le status a 'cancelled'
    et envoie un FCM a tous pour fermer les modals ouverts.
    """
    user_id = uuid.UUID(current["user_id"])
    result = await session.execute(
        select(ObjectiveProposition).where(ObjectiveProposition.id == proposition_id)
    )
    prop = result.scalar_one_or_none()
    if prop is None:
        raise LocalizedHTTPException(status_code=404, error_code="PROPOSITION_NOT_FOUND")
    if prop.proposer_id != user_id:
        raise LocalizedHTTPException(status_code=403, error_code="PROPOSITION_ONLY_PROPOSER_CANCELS")
    if prop.status != "pending":
        raise LocalizedHTTPException(
            status_code=409,
            error_code="PROPOSITION_ALREADY_FORMAT",
            format_args={"status": prop.status},
        )

    prop.status = "cancelled"
    await session.commit()

    # FCM notify a tous les users du foyer pour qu'ils ferment leurs modals
    users_result = await session.execute(
        select(User.id)
        .join(UserFoyer, UserFoyer.user_id == User.id)
        .where(UserFoyer.foyer_id == prop.foyer_id, UserFoyer.left_at.is_(None))
    )
    await send_data_message_to_users(
        session,
        list(users_result.scalars().all()),
        data={
            "type": "objective_proposition_cancelled",
            "proposition_id": str(prop.id),
        },
    )
    return {"ok": True}


@router.get("/{proposition_id}", response_model=PropositionView)
async def get_proposition(
    proposition_id: uuid.UUID,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Detail d'une proposition par id. Utilise par le proposer pour
    rafraichir son ecran d'attente via poll de fallback.
    """
    user_id = uuid.UUID(current["user_id"])
    result = await session.execute(
        select(ObjectiveProposition).where(ObjectiveProposition.id == proposition_id)
    )
    prop = result.scalar_one_or_none()
    if prop is None:
        raise LocalizedHTTPException(status_code=404, error_code="PROPOSITION_NOT_FOUND")
    # Check : user est membre du foyer
    membership = await session.execute(
        select(UserFoyer).where(
            UserFoyer.user_id == user_id,
            UserFoyer.foyer_id == prop.foyer_id,
        )
    )
    if membership.scalar_one_or_none() is None:
        raise LocalizedHTTPException(status_code=403, error_code="FOYER_NOT_MEMBER")

    await _expire_if_needed(session, prop)
    return await _build_proposition_view(session, prop)
