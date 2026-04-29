"""Table de jointure N:N entre users et foyers.

Un user peut appartenir a plusieurs foyers (ex: son propre foyer + la colloc
d'amis). Le foyer "actif" de l'user est stocke separement dans
users.last_active_foyer_id (source de verite au login).
"""

import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, Integer, String, Text, func
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base


class UserFoyer(Base):
    """Relation N:N user<->foyer avec un role (admin, member)."""
    __tablename__ = "user_foyers"

    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    foyer_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("foyers.id", ondelete="CASCADE"), primary_key=True
    )
    # "admin" peut modifier les settings du foyer, inviter/kicker. "member" non.
    role: Mapped[str] = mapped_column(String(20), default="member", nullable=False)
    joined_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    # SSIDs WiFi maison pour ce foyer (liste separee par "|"). Per-foyer
    # parce qu'un user dans 2 foyers a potentiellement 2 lieux physiques
    # differents avec 2 WiFi differents.
    home_ssids: Mapped[str] = mapped_column(Text, default="", nullable=False)
    # Objectif perso min/j que l'user s'est fixe via le popup de simulation
    # (Feature 3 magic buttons v0.8.0). Null = pas d'objectif defini, on
    # retombe sur le rythme moyen 14j pour l'affichage.
    daily_objective_minutes: Mapped[int | None] = mapped_column(
        Integer, nullable=True
    )
    # Preference d'affichage du slider et des cards : 'min' ou 'tasks'. Certains
    # users preferent raisonner en nombre de taches/jour plutot qu'en minutes.
    objective_display_unit: Mapped[str] = mapped_column(
        String(10), default="min", server_default="min", nullable=False
    )
    # v0.30 : soft-leave. Quand un membre quitte le foyer, on positionne
    # left_at = now() et role = 'former_member'. Historique (completions,
    # votes) preserve. Les calculs de consensus excluent les former_members.
    left_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
