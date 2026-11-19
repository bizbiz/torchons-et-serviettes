"""Modele reglages utilisateur — stocke cote serveur pour sync multi-appareils."""

import uuid
from datetime import datetime

from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, String, Text, func
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base


class UserSettings(Base):
    """Reglages personnels d'un utilisateur. Un enregistrement par user."""
    __tablename__ = "user_settings"

    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"),
        primary_key=True
    )
    # Notifications
    notif_daily: Mapped[bool] = mapped_column(Boolean, default=True)
    notif_urgent: Mapped[bool] = mapped_column(Boolean, default=True)
    # Trois flags orthogonaux pour les completions (remplacent notif_completed) :
    # solo = quelqu'un d'autre fait une tache seul
    # collective_with_me = collectif qui inclut ce user (et qu'il n'a pas enregistre lui-meme)
    # collective_without_me = collectif qui ne l'inclut pas (utile a partir de 3 membres)
    notif_solo: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    notif_collective_with_me: Mapped[bool] = mapped_column(
        Boolean, default=False, nullable=False
    )
    notif_collective_without_me: Mapped[bool] = mapped_column(
        Boolean, default=False, nullable=False
    )
    notif_hour: Mapped[int] = mapped_column(Integer, default=9)
    # Hour of the « tasks that turned overdue » notification (toggle: notif_urgent).
    notif_overdue_hour: Mapped[int] = mapped_column(
        Integer, default=8, server_default="8", nullable=False
    )
    # Affinite
    affinity_popup_enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    affinity_expiry_months: Mapped[int] = mapped_column(
        Integer, default=24, server_default="24", nullable=False
    )
    # Tolerance fair-share : message "tu as fait plus que ta part" affiche
    # quand actual > target * (1 + tolerance/100). Defaut 30 = +30%.
    fair_share_tolerance_percent: Mapped[int] = mapped_column(Integer, default=30)
    # v0.32 : toggle global de la gamification (points, bonus, stats,
    # leaderboard, streaks, badges, fair-share). Quand False, le front
    # masque tout l'UI gamif pour ce user, quel que soit le foyer courant.
    # Combinaison OR avec Foyer.gamification_enabled cote frontend.
    gamification_enabled: Mapped[bool] = mapped_column(
        Boolean, default=True, server_default="true", nullable=False
    )
    # Masquer les taches de categorie requires_home de la liste quand le
    # device n'est pas sur un WiFi maison. Per-user (s'applique a tous ses
    # foyers via le flow TokenManager). Defaut True = comportement historique.
    hide_home_tasks_off_wifi: Mapped[bool] = mapped_column(
        Boolean, default=True, server_default="true", nullable=False
    )
    # Cadence of the automatic rating pop-up (app/services/rating_cadence.py).
    # NULL next_at = never scheduled, due now. Written only by POST /api/rating-prompt/outcome.
    rating_prompt_gap_days: Mapped[int] = mapped_column(
        Integer, default=1, server_default="1", nullable=False
    )
    rating_prompt_next_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    # « À faire aujourd'hui » reminders at 12:00, 16:00 and 20:00 Paris time (personal switch;
    # the grouped notification itself cannot be muted alone, docs/plans/a-faire-aujourdhui.md).
    notif_today_reminders: Mapped[bool] = mapped_column(
        Boolean, default=True, server_default="true", nullable=False
    )
    # Server-owned: when this member last got an « À faire aujourd'hui » notification (grouped
    # or reminder). A reminder waits until it is more than an hour old. Never written by /sync.
    today_notified_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    # NOTE : home_ssids a ete deplace sur user_foyers (per-foyer) dans la
    # migration h6c7d8e9f0a1 v0.7.2+. Un user dans 2 foyers a 2 lieux physiques
    # differents donc 2 listes de WiFi distinctes.
    # Timestamps
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )
