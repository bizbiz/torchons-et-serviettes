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
    fcm_token: Mapped[str] = mapped_column(String(500), nullable=False)
    device_name: Mapped[str | None] = mapped_column(String(200), nullable=True)
    platform: Mapped[str] = mapped_column(String(20), default="android")
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
