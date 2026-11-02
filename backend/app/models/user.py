"""Modele utilisateur. Multi-foyer via la table user_foyers."""

import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, String
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, UUIDMixin, TimestampMixin


class User(Base, UUIDMixin, TimestampMixin):
    __tablename__ = "users"

    username: Mapped[str] = mapped_column(String(50), unique=True, nullable=False)
    display_name: Mapped[str] = mapped_column(String(100), nullable=False)
    avatar_url: Mapped[str | None] = mapped_column(String(500), nullable=True)

    # Foyer actuellement actif (celui regardé au dernier login / switch).
    # Source de vérité au login pour savoir quel foyer mettre dans le JWT.
    # ON DELETE SET NULL : si le foyer est supprimé, le user garde son compte
    # mais doit rejoindre / créer un nouveau foyer.
    last_active_foyer_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("foyers.id", ondelete="SET NULL"),
        nullable=True,
    )

    # v0.30 : code de recuperation hashe argon2id. Genere a la creation du
    # foyer (admin) ou au join (member). Renouvelable depuis Reglages.
    recovery_code_hash: Mapped[str | None] = mapped_column(String(255), nullable=True)
    # Deterministic key of the code (utils/recovery_code.lookup_key): restore finds
    # the account by it instead of an argon2 verify per account. NULL for codes
    # issued before 2026-10-12, until they are regenerated or used once.
    recovery_lookup: Mapped[str | None] = mapped_column(String(64), nullable=True, index=True)
    recovery_code_last_used_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )

    # v0.30 : RGPD soft-delete. Endpoint DELETE /me met deleted_at = now() ;
    # job daily purge les rows ou deleted_at + 30j < now(). Toutes les
    # requetes lectures filtrent deleted_at IS NULL.
    deleted_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    # Since 2026-11-02 the account is ANONYMIZED, not deleted, 30 days after it left its last
    # household or was deleted: its completions stay in the household history under a neutral
    # name, and nothing can bring it back (services/member_departure.py).
    anonymized_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
