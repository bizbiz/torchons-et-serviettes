"""Modele utilisateur. Multi-foyer via la table user_foyers."""

import uuid

from sqlalchemy import ForeignKey, String
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, UUIDMixin, TimestampMixin


class User(Base, UUIDMixin, TimestampMixin):
    __tablename__ = "users"

    username: Mapped[str] = mapped_column(String(50), unique=True, nullable=False)
    display_name: Mapped[str] = mapped_column(String(100), nullable=False)
    avatar_url: Mapped[str | None] = mapped_column(String(500), nullable=True)

    # Foyer actuellement actif (celui regardé au dernier login / switch).
    # Source de vérité au login pour savoir quel foyer mettre dans le JWT.
    # ON DELETE SET NULL : si le foyer est supprimé, le user garde son compte
    # mais doit rejoindre / créer un nouveau foyer.
    last_active_foyer_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("foyers.id", ondelete="SET NULL"),
        nullable=True,
    )
