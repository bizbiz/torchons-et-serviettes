"""Propositions d'objectifs fair-share (v0.8.1 Feature 3 magic buttons).

Un user propose a son foyer un plan d'objectifs (minutes/j pour chacun).
Les autres users doivent tous accepter dans les 3 minutes pour que les
objectifs soient persistes sur user_foyers.daily_objective_minutes.

Flow :
- Proposer cree la proposition (status=pending)
- FCM push aux autres users du foyer (pas de notification visible)
- Chaque autre user POST /{id}/respond {accepted: true|false}
- Si tous accepted -> persist sur user_foyers + status=accepted + celebration
- Si un declined -> status=declined, pas de persist
- Si timeout 3 min -> status=expired, pas de persist

L'expiration est checked-on-read : pas de worker cron. A chaque lecture
d'une proposition pending, on check expires_at et on update status si
besoin.
"""

import uuid
from datetime import datetime

from sqlalchemy import Boolean, DateTime, ForeignKey, Index, String, func
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base


class ObjectiveProposition(Base):
    """Une proposition active (ou historique) d'objectifs pour un foyer."""
    __tablename__ = "objective_propositions"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    foyer_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("foyers.id", ondelete="CASCADE"),
        nullable=False,
    )
    proposer_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
    )
    # {user_id: minutes/j} pour tous les users du foyer proposes.
    slider_values_json: Mapped[dict] = mapped_column(JSONB, nullable=False)
    # 'pending' / 'accepted' / 'declined' / 'expired'
    status: Mapped[str] = mapped_column(
        String(20), default="pending", server_default="pending", nullable=False
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    expires_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )

    __table_args__ = (
        Index(
            "ix_objective_propositions_foyer_status",
            "foyer_id",
            "status",
        ),
    )


class ObjectivePropositionResponse(Base):
    """Reponse d'un user a une proposition (accept / decline)."""
    __tablename__ = "objective_proposition_responses"

    proposition_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("objective_propositions.id", ondelete="CASCADE"),
        primary_key=True,
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="CASCADE"),
        primary_key=True,
    )
    accepted: Mapped[bool] = mapped_column(Boolean, nullable=False)
    responded_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
