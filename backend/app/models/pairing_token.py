"""Modele pairing_tokens — tokens ephemeres pour rejoindre un foyer via QR.

Genere a la demande par l'owner via POST /pairing/init. Encode dans le QR
affiche a l'owner. Le joiner scanne, appelle POST /pairing/join {token,
display_name}, le backend cree User + UserFoyer, marque le token consomme.

Cycle de vie :
- created_at : creation
- expires_at : created_at + 15 min (TTL)
- consumed_at : non-null quand le joiner a effectivement rejoint

Single-use : un token consomme ne peut plus etre re-utilise.
"""

from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, String, func
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base


class PairingToken(Base):
    __tablename__ = "pairing_tokens"

    # Token clair (16 chars hex). Sert a la fois de PK et de matiere
    # encodee dans le QR. Pas hashe — il est ephemere et single-use, et
    # sa valeur ne donne aucun pouvoir une fois consomme.
    token: Mapped[str] = mapped_column(String(32), primary_key=True)

    foyer_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("foyers.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    # User qui a genere le token (admin ou member, on accepte les deux).
    created_by_user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    expires_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, index=True
    )
    consumed_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    # v0.31 : 'foyer_join' (defaut, nouveau member) vs 'add_device' (ajouter
    # un de mes propres appareils -> meme user_id, nouveau device).
    kind: Mapped[str] = mapped_column(
        String(20), default="foyer_join",
        server_default="foyer_join", nullable=False,
    )
