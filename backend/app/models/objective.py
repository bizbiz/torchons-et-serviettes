"""Modele objectifs personnels et partages (v0.31).

Roadmap user-facing : "Objectifs personnels et partages (ex : 'nettoyer
la cuisine 3 fois par semaine')". Chaque objectif a une fenetre temporelle
(jour/semaine/mois), un nombre cible de completions, et optionnellement un
scope (piece ou categorie).

Pour `kind=personal`, l'objectif appartient a un user specifique
(`assigned_to_user_id`). Pour `kind=shared`, c'est un objectif foyer-wide
auquel tous les members contribuent.

Le calcul du `progress_count` se fait dynamiquement cote endpoint, en
joignant TaskCompletion sur la fenetre temporelle + scope.
"""

from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, String, func
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, UUIDMixin, TimestampMixin, SoftDeleteMixin


class Objective(Base, UUIDMixin, TimestampMixin, SoftDeleteMixin):
    __tablename__ = "objectives"

    foyer_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("foyers.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    title: Mapped[str] = mapped_column(String(120), nullable=False)
    # 'personal' (assigned_to_user_id requis) ou 'shared' (foyer-wide).
    kind: Mapped[str] = mapped_column(String(16), nullable=False, default="shared")
    # 'day' / 'week' / 'month' — granularite de la fenetre.
    period: Mapped[str] = mapped_column(String(8), nullable=False, default="week")
    target_count: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    # Scope optionnel : si null, comptabilise toutes les completions du foyer
    # (filtrees par user pour kind=personal).
    scope_context_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("contexts.id", ondelete="SET NULL"),
        nullable=True,
    )
    assigned_to_user_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="SET NULL"),
        nullable=True,
    )
    is_active: Mapped[bool] = mapped_column(
        Boolean, nullable=False, default=True, server_default="true"
    )
