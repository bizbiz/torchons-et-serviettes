"""Tokens FCM pour notifications push (P4)."""

import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, String, UniqueConstraint, func
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, UUIDMixin


class NotificationToken(Base, UUIDMixin):
    __tablename__ = "notification_tokens"
    __table_args__ = (UniqueConstraint("user_id", "fcm_token"),)

    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id"), nullable=False
    )
    # The push target. Despite its name, it carries an FCM registration token (`kind` = "token",
    # APKs before 2026-11-12) OR a Firebase Installation ID (`kind` = "fid", the app since):
    # FCM deprecated the token for the FID (migration f3b8d5c2e9a1, services/push.py).
    fcm_token: Mapped[str] = mapped_column(String(500), nullable=False)
    kind: Mapped[str] = mapped_column(String(8), nullable=False, server_default="token")
    device_name: Mapped[str | None] = mapped_column(String(200), nullable=True)
    # Quel APPAREIL detient ce jeton FCM. Nullable : les lignes creees avant
    # cette colonne n'en ont pas, et un jeton legacy sans claim `device_id`
    # n'en produira jamais. Rempli depuis le JWT et non depuis le corps de la
    # requete — le client ne peut pas mentir dessus sans mentir sur son jeton.
    # Sert a couper les notifications quand l'appareil est revoque : sans lui,
    # un telephone coupe continuait de recevoir les taches du foyer.
    # Voir docs/appareils.fr.md.
    device_id: Mapped[str | None] = mapped_column(String(64), nullable=True, index=True)
    platform: Mapped[str] = mapped_column(String(20), default="android")
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
