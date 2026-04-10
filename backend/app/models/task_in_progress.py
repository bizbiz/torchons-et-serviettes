"""Modele tache en cours — reserve une tache pour un utilisateur."""

import uuid
from datetime import datetime, timezone

from sqlalchemy import DateTime, ForeignKey, func
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base


class TaskInProgress(Base):
    """Marque qu'un utilisateur a commence une tache.
    Timeout automatique apres 6h — la tache redevient disponible.
    """
    __tablename__ = "task_in_progress"

    task_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("tasks.id", ondelete="CASCADE"), primary_key=True
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    started_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
