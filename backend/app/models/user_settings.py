"""Modele reglages utilisateur — stocke cote serveur pour sync multi-appareils."""

import uuid
from datetime import datetime

from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, String, Text, func
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base


class UserSettings(Base):
    """Reglages personnels d'un utilisateur. Un enregistrement par user."""
    __tablename__ = "user_settings"

    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"),
        primary_key=True
    )
    # Notifications
    notif_daily: Mapped[bool] = mapped_column(Boolean, default=True)
    notif_urgent: Mapped[bool] = mapped_column(Boolean, default=True)
    notif_completed: Mapped[bool] = mapped_column(Boolean, default=False)
    notif_hour: Mapped[int] = mapped_column(Integer, default=9)
    # Affinite
    affinity_popup_enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    affinity_expiry_months: Mapped[int] = mapped_column(Integer, default=6)
    # Suggestions
    freshness_threshold: Mapped[int] = mapped_column(Integer, default=10)
    # WiFi maison (SSIDs separes par |)
    home_ssids: Mapped[str] = mapped_column(Text, default="")
    # Timestamps
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )
