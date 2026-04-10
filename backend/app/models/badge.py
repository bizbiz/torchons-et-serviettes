"""Modele badge — achievements gagnes par les utilisateurs."""

import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, String, func
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, UUIDMixin


class Badge(Base, UUIDMixin):
    """Badge gagne par un utilisateur.
    badge_type identifie le type de badge (ex: 'first_task', 'streak_7', 'tasks_100').
    """
    __tablename__ = "badges"

    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    badge_type: Mapped[str] = mapped_column(String(50), nullable=False)
    earned_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
