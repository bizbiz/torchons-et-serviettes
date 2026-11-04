"""Config fair-share — ratio cible par (user, foyer) (P3)."""

import uuid
from datetime import datetime

from sqlalchemy import DateTime, Float, ForeignKey, Index, UniqueConstraint, func
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, UUIDMixin


class FairShareConfig(Base, UUIDMixin):
    """Ratio cible per (user, foyer). Un user peut avoir un ratio different
    dans chaque foyer auquel il appartient (ex: 60% chez moi, 20% chez les potes).
    """
    __tablename__ = "fair_share_config"

    foyer_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("foyers.id", ondelete="CASCADE"), nullable=False
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id"), nullable=False
    )
    target_ratio: Mapped[float] = mapped_column(Float, default=0.5, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )

    __table_args__ = (
        UniqueConstraint('user_id', 'foyer_id', name='uq_fair_share_user_foyer'),
    )


class FairShareHistory(Base, UUIDMixin):
    """One row per accepted PUT /fair-share/config: who changed the targets, when,
    and the target of every member before and after.

    `before` / `after` map str(user_id) -> ratio. `before` holds the targets that were
    IN FORCE (the 1/N default for a member without a row), so the history tells what
    the household saw, not what the table held. Names are resolved when the history is
    read, never stored: an anonymized account must not keep its name here.
    """
    __tablename__ = "fair_share_history"

    foyer_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("foyers.id", ondelete="CASCADE"), nullable=False
    )
    changed_by: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id"), nullable=False
    )
    changed_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    before: Mapped[dict] = mapped_column(JSONB, nullable=False)
    after: Mapped[dict] = mapped_column(JSONB, nullable=False)

    __table_args__ = (
        Index("ix_fair_share_history_foyer_changed_at", "foyer_id", "changed_at"),
    )
