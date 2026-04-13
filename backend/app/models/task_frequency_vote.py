"""Modele vote de frequence — every_n_days souhaite par utilisateur.

La moyenne arrondie des votes constitue le consensus et est ecrite
dans tasks.recurrence_rule.every_n_days a chaque vote.
"""

import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, Integer, func
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base


class TaskFrequencyVote(Base):
    """Vote d'un utilisateur sur la frequence d'execution d'une tache.
    every_n_days : nombre de jours entre deux executions souhaite par l'user.
    Le consensus (moyenne arrondie) est stocke dans tasks.recurrence_rule.
    """
    __tablename__ = "task_frequency_votes"

    task_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("tasks.id", ondelete="CASCADE"), primary_key=True
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    every_n_days: Mapped[int] = mapped_column(Integer, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )
