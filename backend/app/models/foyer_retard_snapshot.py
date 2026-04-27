"""Snapshot journalier du retard agrege d'un foyer (v0.22).

Une ligne par foyer par jour — calculee a 03h UTC par APScheduler.
Backfill historique via reconstruction depuis les recurrences. Voir
`app.services.retard_history`.
"""

import uuid
from datetime import date, datetime

from sqlalchemy import Date, DateTime, ForeignKey, Integer, PrimaryKeyConstraint, func
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base


class FoyerRetardSnapshot(Base):
    __tablename__ = "foyer_retard_snapshots"
    __table_args__ = (PrimaryKeyConstraint("foyer_id", "date"),)

    foyer_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("foyers.id", ondelete="CASCADE"), nullable=False
    )
    date: Mapped[date] = mapped_column(Date, nullable=False)
    total_minutes: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    task_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    computed_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
