"""Completions — log immutable des taches faites."""

import uuid
from datetime import datetime

from sqlalchemy import DateTime, Enum as SAEnum, Float, ForeignKey, Integer, Text, func
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, UUIDMixin, SoftDeleteMixin
from app.models.enums import CompletionReason


class TaskCompletion(Base, UUIDMixin, SoftDeleteMixin):
    __tablename__ = "task_completions"

    task_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("tasks.id"), nullable=False
    )
    completed_by: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id"), nullable=False
    )
    completed_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    points_earned: Mapped[int] = mapped_column(Integer, default=1)
    # Tracabilite immuable du multiplicateur bonus/malus applique au moment
    # de la completion (cf services/bonus_malus.py). Fige pour ne pas
    # reecrire l'historique si les seuils foyer_bonus_config changent.
    multiplier: Mapped[float] = mapped_column(Float, nullable=False, default=1.0)
    reason: Mapped[CompletionReason] = mapped_column(
        SAEnum(CompletionReason, name="completion_reason"),
        nullable=False,
        default=CompletionReason.NEUTRAL,
    )
    notes: Mapped[str | None] = mapped_column(Text, nullable=True)
    client_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    # Groupe les lignes d'une meme completion "fait ensemble" (NULL = solo)
    collective_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True), nullable=True, index=True
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )
