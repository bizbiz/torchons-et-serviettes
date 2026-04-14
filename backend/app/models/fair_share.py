"""Config fair-share — ratio cible par (user, foyer) (P3)."""

import uuid
from datetime import datetime

from sqlalchemy import DateTime, Float, ForeignKey, UniqueConstraint, func
from sqlalchemy.dialects.postgresql import UUID
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
