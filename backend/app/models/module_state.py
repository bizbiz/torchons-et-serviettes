"""Module enablement state — progressive disclosure (P1).

One row per enabled/disabled decision. A NULL `user_id` means the row carries
a COLLECTIVE decision, valid for every member of the foyer; a non-NULL one
carries a PERSONAL decision, scoped to that member within that foyer.

Absence of a row is meaningful: it means "never decided", and the effective
state falls back to `default_new` from modules.yaml. The backfill migration
writes explicit rows for every pre-existing foyer, so a foyer with no rows is
necessarily one created after the switchover — which is exactly the population
that should start with the core only.

Scope is NOT stored here. It is a property of the module, declared once in
modules.yaml, and the API refuses a write whose scope contradicts it. Storing
it would allow the two to drift.
"""

import uuid
from datetime import datetime

from sqlalchemy import (
    Boolean,
    DateTime,
    ForeignKey,
    Index,
    String,
    func,
)
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, UUIDMixin


class ModuleState(Base, UUIDMixin):
    """Enablement of one module, for a foyer or for a member within it."""

    __tablename__ = "module_states"

    foyer_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("foyers.id", ondelete="CASCADE"),
        nullable=False,
    )
    # NULL = collective decision. Postgres treats NULLs as distinct in a
    # unique index, hence the two partial indexes below rather than one
    # three-column unique constraint.
    user_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"),
        nullable=True,
    )
    module_key: Mapped[str] = mapped_column(String(50), nullable=False)
    enabled: Mapped[bool] = mapped_column(Boolean, nullable=False)

    # Set when the module last transitioned to enabled. Kept for the reveal
    # copy ("enabled on X") and for future analytics; deliberately NOT used to
    # window any computation — the decision was full reveal, so counters run
    # over the whole history.
    enabled_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now(),
        nullable=False,
    )
    # Who flipped it. Collective toggles are open to any member, so the trace
    # is what makes "the fair share vanished" explainable rather than a mystery.
    updated_by_user_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="SET NULL"),
        nullable=True,
    )

    __table_args__ = (
        Index(
            "uq_module_states_collective",
            "foyer_id", "module_key",
            unique=True,
            postgresql_where="user_id IS NULL",
        ),
        Index(
            "uq_module_states_personal",
            "foyer_id", "user_id", "module_key",
            unique=True,
            postgresql_where="user_id IS NOT NULL",
        ),
        Index("ix_module_states_foyer", "foyer_id"),
    )
