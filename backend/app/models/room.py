"""Modele piece (room) — per-foyer, gere dans Settings."""

import uuid

from sqlalchemy import ForeignKey, Index, String, text
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, UUIDMixin, TimestampMixin, SoftDeleteMixin


class Room(Base, UUIDMixin, TimestampMixin, SoftDeleteMixin):
    """Une piece de la maison : Cuisine, SDB, Chambre, etc.

    name est unique par foyer (composite unique). icon (Material name) et
    emoji sont optionnels. color permet de differencier deux pieces du
    meme type (ex: "Chambre verte" vs "Chambre bleue").

    Tri alphabetique cote API (pas de sort_order).
    """
    __tablename__ = "rooms"

    foyer_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("foyers.id", ondelete="CASCADE"), nullable=False
    )
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    color: Mapped[str] = mapped_column(String(7), default="#6B7280")
    icon: Mapped[str | None] = mapped_column(String(50), nullable=True)
    emoji: Mapped[str | None] = mapped_column(String(10), nullable=True)
    # Si cette piece a ete importee depuis le catalogue (v0.9.0 Feature 4).
    template_key: Mapped[str | None] = mapped_column(String(50), nullable=True)

    __table_args__ = (
        # Unicite PARMI LES VIVANTS : une unique simple interdirait de
        # recreer un nom qu'on vient de supprimer (soft delete).
        Index('uq_rooms_name_foyer', 'foyer_id', 'name', unique=True,
              postgresql_where=text('deleted_at IS NULL')),
    )
