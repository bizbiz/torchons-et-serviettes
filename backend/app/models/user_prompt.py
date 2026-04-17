"""Modele user_prompts — queue unifiee de questions a poser a l'utilisateur.

Remplace le systeme ad-hoc /pending-affinity + /oldest-rating par une table
unique priorisee. Le backend genere les prompts (event-driven + cron), le
front pioche 1 a la fois via GET /api/prompts/next.

Types : BUMP_EFFORT (tache en retard systematique), VOTE_RATING (notation
affinite+effort combinee). Extensible via l'enum PromptType.

Le champ `context` JSONB contient un snapshot fige au moment de la creation
du prompt (pas recalcule). Le front affiche le contexte tel quel.
"""

import uuid
from datetime import datetime

from sqlalchemy import DateTime, Enum as SAEnum, Float, ForeignKey, Integer, func
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, UUIDMixin
from app.models.enums import PromptResolution, PromptType


class UserPrompt(Base, UUIDMixin):
    __tablename__ = "user_prompts"

    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id"), nullable=False
    )
    foyer_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("foyers.id", ondelete="CASCADE"), nullable=False
    )
    type: Mapped[PromptType] = mapped_column(
        SAEnum(PromptType, name="prompt_type"), nullable=False
    )
    priority: Mapped[int] = mapped_column(Integer, nullable=False, default=5)
    context: Mapped[dict] = mapped_column(JSONB, nullable=False, default=dict)

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    shown_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    resolved_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    resolution: Mapped[PromptResolution | None] = mapped_column(
        SAEnum(PromptResolution, name="prompt_resolution"), nullable=True
    )
    expires_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    # Grace period : pour BUMP_EFFORT, date jusqu'a laquelle on ne re-dispatche
    # pas de bump pour cette tache. Stocke comme colonne pour queries efficaces.
    grace_until: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
