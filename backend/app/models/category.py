"""Categories de taches (menage, courses, admin, perso)."""

from sqlalchemy import Boolean, Integer, String
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, UUIDMixin, TimestampMixin, SoftDeleteMixin


class Category(Base, UUIDMixin, TimestampMixin, SoftDeleteMixin):
    __tablename__ = "categories"

    name: Mapped[str] = mapped_column(String(100), unique=True, nullable=False)
    color: Mapped[str] = mapped_column(String(7), default="#6B7280")
    icon: Mapped[str | None] = mapped_column(String(50), nullable=True)
    sort_order: Mapped[int] = mapped_column(Integer, default=0)
    requires_home: Mapped[bool] = mapped_column(Boolean, default=False)
