"""Modele tache — table centrale avec recurrence JSONB."""

import uuid

from sqlalchemy import Boolean, Float, ForeignKey, Integer, String, Text
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, UUIDMixin, TimestampMixin, SoftDeleteMixin


class Task(Base, UUIDMixin, TimestampMixin, SoftDeleteMixin):
    __tablename__ = "tasks"

    title: Mapped[str] = mapped_column(String(300), nullable=False)
    description: Mapped[str | None] = mapped_column(Text, nullable=True)
    category_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True), ForeignKey("categories.id"), nullable=True
    )

    # Recurrence — JSONB flexible (voir CLAUDE.md pour les formats)
    recurrence_rule: Mapped[dict | None] = mapped_column(JSONB, nullable=True)

    # Gamification (P2)
    weight: Mapped[int] = mapped_column(Integer, default=1)
    freshness_half_life_hours: Mapped[float | None] = mapped_column(Float, nullable=True)

    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    sort_order: Mapped[int] = mapped_column(Integer, default=0)
