"""Config fair-share — ratio cible par utilisateur (P3)."""

import uuid
from datetime import datetime

from sqlalchemy import DateTime, Float, ForeignKey, func
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, UUIDMixin


class FairShareConfig(Base, UUIDMixin):
    __tablename__ = "fair_share_config"

    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id"), unique=True, nullable=False
    )
    target_ratio: Mapped[float] = mapped_column(Float, default=0.5, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
