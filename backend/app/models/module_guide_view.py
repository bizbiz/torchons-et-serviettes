"""Which module guide pages a member has already seen.

One row per (user, module). The key is the USER, not the (user, foyer) pair: a
guide explains what a module is for, and a person who read it in one household
does not need it again in another. Storing it server-side is what makes "shown
once" hold across devices and reinstalls, which a client-side flag cannot.

Absence of a row means "never seen". Rows are only ever inserted (idempotent
`ON CONFLICT DO NOTHING`), never updated or deleted by the application.
"""

import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, PrimaryKeyConstraint, String, func
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base


class ModuleGuideView(Base):
    __tablename__ = "module_guide_views"
    __table_args__ = (PrimaryKeyConstraint("user_id", "module_key"),)

    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    module_key: Mapped[str] = mapped_column(String(50), nullable=False)
    seen_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
