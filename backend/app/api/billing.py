"""Endpoints Google Play Billing — STUB v0.30.1 (plomberie, pas de paywall).

But : avoir la tuyauterie en place pour pouvoir flipper le paywall en
quelques lignes en v2 post-launch (~M+12). Aucune feature gated derriere
`is_premium` a ce stade.

Deux endpoints :
- POST /api/billing/verify : appele par l'app Android apres un achat
  via BillingClient. Recoit le `purchase_token` Google + le `product_id`
  + un device_id. STUB v0.30.1 : flippe `foyer.is_premium = true` sans
  appeler Google Play Developer API. La vraie implementation arrivera
  quand on aura un service account Google Play Console (cf. Phase E
  roadmap dev).
- POST /api/billing/rtdn : webhook Pub/Sub pour les Real-Time Developer
  Notifications (renouvellement, annulation, refund). STUB v0.30.1 :
  log l'event + 200 OK. La vraie implementation parsera le payload Pub/Sub
  et mettra a jour `foyer.is_premium` / `premium_until` en consequence.

Cf. docs/design-onboarding-public.md §7 et roadmap-dev-public-launch §C.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone
from typing import Any

import structlog
from fastapi import APIRouter, Depends

from app.utils.errors import LocalizedHTTPException
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.models.foyer import Foyer
from app.models.user_foyer import UserFoyer

logger = structlog.get_logger()

router = APIRouter(prefix="/api/billing", tags=["billing"])


class VerifyRequest(BaseModel):
    purchase_token: str = Field(min_length=1)
    product_id: str = Field(min_length=1)
    # device_id non strictement necessaire (le JWT a deja le device_id),
    # mais on l'accepte pour audit.
    device_id: str | None = None


class VerifyResponse(BaseModel):
    is_premium: bool
    premium_until: datetime | None


@router.post("/verify", response_model=VerifyResponse)
async def verify_purchase(
    body: VerifyRequest,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """STUB v0.30.1 : flippe `foyer.is_premium` sans verification Google.

    Quand le service account Google Play sera configure, cette fonction
    devra :
    1. Appeler GooglePlayDeveloperAPI.purchases.subscriptions.get(
         packageName, subscriptionId, purchaseToken)
    2. Verifier que `paymentState == 1` (PAID) ou `expiryTimeMillis > now`
    3. Lire `expiryTimeMillis` -> `premium_until`
    4. Acknowledger via purchases.subscriptions.acknowledge si pas deja fait

    Pour la v0.30.1, on accepte tout `purchase_token` non vide et on flippe
    `is_premium = true` avec `premium_until = now + 30j` (simulation d'un
    abonnement mensuel) afin de pouvoir tester l'UI cote client.
    """
    user_id = uuid.UUID(current["user_id"])
    foyer_id = uuid.UUID(current["foyer_id"])

    # Verifie que l'user est admin du foyer (seuls les admins peuvent
    # activer le premium pour le foyer entier).
    membership = await session.execute(
        select(UserFoyer).where(
            UserFoyer.user_id == user_id,
            UserFoyer.foyer_id == foyer_id,
            UserFoyer.left_at.is_(None),
        )
    )
    uf = membership.scalar_one_or_none()
    if uf is None or uf.role != "admin":
        raise LocalizedHTTPException(
            status_code=403,
            error_code="BILLING_ADMIN_REQUIRED",
        )

    foyer_result = await session.execute(select(Foyer).where(Foyer.id == foyer_id))
    foyer = foyer_result.scalar_one_or_none()
    if foyer is None or foyer.deleted_at is not None:
        raise LocalizedHTTPException(status_code=404, error_code="FOYER_NOT_FOUND")

    # STUB : on simule un abonnement mensuel.
    foyer.is_premium = True
    foyer.premium_until = datetime.now(timezone.utc) + timedelta(days=30)
    await session.commit()

    logger.info(
        "billing_verify_stub",
        foyer_id=str(foyer_id),
        product_id=body.product_id,
        purchase_token_prefix=body.purchase_token[:12],
        premium_until=foyer.premium_until.isoformat(),
    )

    return VerifyResponse(
        is_premium=foyer.is_premium,
        premium_until=foyer.premium_until,
    )


class RtdnEnvelope(BaseModel):
    """Payload Pub/Sub Google Play RTDN (forme generique).

    Le vrai payload Pub/Sub aura `message.data` en base64 contenant un
    JSON `{notificationType, purchaseToken, ...}`. STUB : on accepte
    n'importe quel dict pour eviter de bloquer.
    """
    message: dict[str, Any] | None = None
    subscription: str | None = None


@router.post("/rtdn")
async def rtdn_webhook(body: RtdnEnvelope):
    """STUB v0.30.1 : log et acknowledge.

    Quand on aura configure Pub/Sub + le webhook URL dans Google Play
    Console, ce endpoint devra :
    1. Decoder `message.data` (base64 -> JSON Pub/Sub)
    2. Lire `subscriptionNotification.notificationType` :
       - 1 (RECOVERED), 2 (RENEWED), 4 (PURCHASED) -> is_premium=True
       - 3 (CANCELED), 12 (REVOKED), 13 (EXPIRED) -> is_premium=False
       - autres -> log only
    3. Lire `purchaseToken`, retrouver le foyer associe (via la table
       a creer `billing_purchases` qui lie purchase_token -> foyer_id)
    4. Mettre a jour le state du foyer en consequence
    5. Acknowledger Pub/Sub en repondant 200

    Pour la v0.30.1, on log juste l'event et on repond 200 pour ne pas
    casser le retry Google.
    """
    logger.info("billing_rtdn_stub", payload=body.model_dump())
    return {"status": "ok"}
