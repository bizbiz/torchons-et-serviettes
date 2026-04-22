"""Modele log analytics des dismiss de suggestions.

Table append-only : chaque fois qu'un utilisateur clique sur ✕ pour
ignorer une suggestion sur l'ecran d'accueil, une ligne est inseree.
Sert uniquement aux stats / analyses a posteriori — aucun impact sur
l'algorithme de selection des suggestions.

Scope : foyer + user. Tier conservé tel que la suggestion était
classée au moment du dismiss (urgent / affinity / uncertain).
"""

import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, String, func, Index
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, UUIDMixin


class SuggestionDismissal(Base, UUIDMixin):
    """Event append-only : un clic sur ✕ sur une carte de suggestion."""
    __tablename__ = "suggestion_dismissals"

    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    foyer_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("foyers.id", ondelete="CASCADE"), nullable=False
    )
    task_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("tasks.id", ondelete="CASCADE"), nullable=False
    )
    tier: Mapped[str] = mapped_column(String(16), nullable=False)
    dismissed_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )

    __table_args__ = (
        Index(
            "ix_suggestion_dismissals_user_foyer_dismissed",
            "user_id", "foyer_id", "dismissed_at",
        ),
    )
