"""Table de jointure N:N entre users et foyers.

Un user peut appartenir a plusieurs foyers (ex: son propre foyer + la colloc
d'amis). Le foyer "actif" de l'user est stocke separement dans
users.last_active_foyer_id (source de verite au login).
"""

import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, String, func
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base


class UserFoyer(Base):
    """Relation N:N user<->foyer avec un role (admin, member)."""
    __tablename__ = "user_foyers"

    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    foyer_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("foyers.id", ondelete="CASCADE"), primary_key=True
    )
    # "admin" peut modifier les settings du foyer, inviter/kicker. "member" non.
    role: Mapped[str] = mapped_column(String(20), default="member", nullable=False)
    joined_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
