"""Modele tache — table centrale avec recurrence JSONB."""

import uuid

from sqlalchemy import Boolean, Enum as SAEnum, Float, ForeignKey, Integer, String, Text
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, UUIDMixin, TimestampMixin, SoftDeleteMixin
from app.models.enums import TaskTolerance


class Task(Base, UUIDMixin, TimestampMixin, SoftDeleteMixin):
    __tablename__ = "tasks"

    # Foyer auquel la tache appartient. CASCADE : supprimer le foyer
    # supprime ses taches. Scoping obligatoire dans toutes les queries.
    foyer_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("foyers.id", ondelete="CASCADE"), nullable=False
    )

    title: Mapped[str] = mapped_column(String(300), nullable=False)
    description: Mapped[str | None] = mapped_column(Text, nullable=True)
    # Piece (room) optionnelle. ON DELETE SET NULL : si la piece est
    # supprimee, la tache reste sans piece au lieu d'etre cascade-deleted.

    # Recurrence — JSONB flexible (formats : docs/contexts.md, CLAUDE.md § Recurrence)
    recurrence_rule: Mapped[dict | None] = mapped_column(JSONB, nullable=True)

    # Intervalle infere en jours pour le type "uncertain" (v0.14). NULL tant
    # qu'on a moins de 2 completions. Mis a jour a chaque completion via
    # compute_inferred_interval_days(). Utilise par /api/suggestions (ranking
    # bucket "Sans cadence") et /api/maintenance-rate.
    inferred_interval_days: Mapped[float | None] = mapped_column(Float, nullable=True)

    # Gamification (P2)
    weight: Mapped[int] = mapped_column(Integer, default=1)
    # Tier de priorite pour le selecteur "la plus urgente" sur la home page.
    # Aucun impact sur l'etat visuel d'une tache ni sur le fait qu'elle soit
    # en retard (cf v0.10.0, refonte freshness -> due_date). Voir TaskTolerance.
    tolerance: Mapped[TaskTolerance] = mapped_column(
        SAEnum(TaskTolerance, name="task_tolerance"),
        nullable=False,
        default=TaskTolerance.NORMAL,
    )

    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    # v0.20 : archivage soft des ponctuelles terminees. Job quotidien met
    # is_archived=true pour toute tache sans recurrence completee depuis
    # plus de ARCHIVE_AFTER_DAYS jours. Toutes les lectures liste filtrent
    # is_archived=false par defaut. Lecture seule cote UI (pas de restore).
    is_archived: Mapped[bool] = mapped_column(
        Boolean, default=False, server_default="false", nullable=False
    )
    sort_order: Mapped[int] = mapped_column(Integer, default=0)
    # Si cette tache a ete importee depuis le catalogue (v0.9.0 Feature 4),
    # on stocke la `key` stable du template d'origine ici pour le tracking
    # d'usage (tri par popularite futur, analyse).
    template_key: Mapped[str | None] = mapped_column(String(50), nullable=True)
