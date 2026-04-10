"""Modele effort — estimation de temps par tache et par utilisateur."""

import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, Integer, func
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base


class TaskEffort(Base):
    """Estimation d'effort d'un utilisateur pour une tache.
    Points lineaires : 1pt = 5min. Slider de 1 (5min) a 18 (1h30).
    La moyenne des estimations de tous les users donne le poids effectif.
    """
    __tablename__ = "task_efforts"

    task_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("tasks.id", ondelete="CASCADE"), primary_key=True
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    points: Mapped[int] = mapped_column(Integer, default=3)  # 1-18, defaut 15min
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )
