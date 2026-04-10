"""Modele de jointure tache-utilisateur — remplace assigned_to."""

import uuid

from sqlalchemy import ForeignKey
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base


class TaskAssignment(Base):
    """Association entre une tache et un utilisateur assigne.
    Une tache avec 1 seul user = perso (visible que par lui).
    Une tache avec >1 users = commune (visible par tous les assignes).
    """
    __tablename__ = "task_assignments"

    task_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("tasks.id", ondelete="CASCADE"), primary_key=True
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
