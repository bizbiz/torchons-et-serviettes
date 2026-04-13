"""Modele piece (room) — gere dans Settings, partage entre tous les users."""

from sqlalchemy import String
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, UUIDMixin, TimestampMixin, SoftDeleteMixin


class Room(Base, UUIDMixin, TimestampMixin, SoftDeleteMixin):
    """Une piece de la maison : Cuisine, SDB, Chambre, etc.

    name est unique. icon (Material name) et emoji sont optionnels —
    le picker UI propose les deux : si icon est defini, on l'affiche en
    priorite, sinon on tombe sur l'emoji. color permet de differencier
    deux pieces du meme type (ex: "Chambre verte" vs "Chambre bleue").

    Tri alphabetique cote API (pas de sort_order).
    """
    __tablename__ = "rooms"

    name: Mapped[str] = mapped_column(String(100), unique=True, nullable=False)
    color: Mapped[str] = mapped_column(String(7), default="#6B7280")
    icon: Mapped[str | None] = mapped_column(String(50), nullable=True)
    emoji: Mapped[str | None] = mapped_column(String(10), nullable=True)
