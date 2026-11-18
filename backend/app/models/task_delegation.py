"""« À faire aujourd'hui »: a task one member hands to one or more other members, for today.

Spec: docs/plans/a-faire-aujourdhui.md. A delegation is ACTIVE while neither `cancelled_at`
(its author took it back) nor `settled_at` (anyone completed the task) is set; at most one is
active per task (partial unique index). It never expires: at midnight it simply stays.

The grouped notification state lives here, per recipient (`announced_at`), so that a restart
loses nothing: the scheduler job reads it back from the database (services/delegation_push.py).
"""

import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, Index, func, text
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base


class TaskDelegation(Base):
    __tablename__ = "task_delegations"

    # Client-generated (the /sync op's row_id): the optimistic local row keeps its id.
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    foyer_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("foyers.id", ondelete="CASCADE"), nullable=False
    )
    task_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("tasks.id", ondelete="CASCADE"), nullable=False
    )
    author_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    cancelled_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    settled_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    __table_args__ = (
        Index(
            "uq_task_delegations_active_task", "task_id", unique=True,
            postgresql_where=text("cancelled_at IS NULL AND settled_at IS NULL"),
        ),
        Index("ix_task_delegations_foyer", "foyer_id"),
    )


class TaskDelegationRecipient(Base):
    """One recipient of a delegation, with the time its grouped notification left."""
    __tablename__ = "task_delegation_recipients"

    delegation_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("task_delegations.id", ondelete="CASCADE"), primary_key=True
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    # NULL = not announced yet. Set when the grouped notification is sent. A delegation that
    # ends inside the quiet window (cancelled or settled) is simply never picked up again.
    announced_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
