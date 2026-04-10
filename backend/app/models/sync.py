"""Change log pour sync offline — compteur monotone global."""

import uuid
from datetime import datetime

from sqlalchemy import BigInteger, DateTime, ForeignKey, String, func
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base


class ChangeLog(Base):
    __tablename__ = "change_log"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    server_version: Mapped[int] = mapped_column(BigInteger, unique=True, autoincrement=True)
    table_name: Mapped[str] = mapped_column(String(50), nullable=False)
    row_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    operation: Mapped[str] = mapped_column(String(10), nullable=False)  # INSERT, UPDATE, DELETE
    changed_by: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id"), nullable=True
    )
    changed_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
    old_data: Mapped[dict | None] = mapped_column(JSONB, nullable=True)
    new_data: Mapped[dict | None] = mapped_column(JSONB, nullable=True)
