"""Config bonus/malus par foyer — seuils, plancher, parametres d'analyse.

Une ligne par foyer (PK = foyer_id). Cree automatiquement lors de la creation
du foyer. Helper `get_or_create_config` garantit l'existence avant lecture
pour les foyers hypothetiques sans ligne (edge case).

Tous les parametres sont reglables via le panneau 'Options avancees' cote
Android. Defauts choisis pour un comportement equilibre en NORMAL (cf
grill-me bonus/malus v1.0).
"""

import uuid
from datetime import datetime

from sqlalchemy import DateTime, Float, ForeignKey, Integer, func
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base


class FoyerBonusConfig(Base):
    __tablename__ = "foyer_bonus_config"

    foyer_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("foyers.id", ondelete="CASCADE"),
        primary_key=True,
    )

    # Seuils malus (fraction du cycle d'avance pour entrer en zone malus).
    # Ex : 0.30 = malus si faite a plus de 30% du cycle avant due_date.
    seuil_malus_strict: Mapped[float] = mapped_column(Float, nullable=False, default=0.10)
    seuil_malus_normal: Mapped[float] = mapped_column(Float, nullable=False, default=0.30)
    seuil_malus_souple: Mapped[float] = mapped_column(Float, nullable=False, default=0.50)

    # Seuils bonus (fraction du cycle de retard pour entrer en zone bonus).
    seuil_bonus_strict: Mapped[float] = mapped_column(Float, nullable=False, default=0.10)
    seuil_bonus_normal: Mapped[float] = mapped_column(Float, nullable=False, default=0.20)
    seuil_bonus_souple: Mapped[float] = mapped_column(Float, nullable=False, default=0.40)

    # Plancher malus : multiplicateur a 100% d'avance (debut de cycle).
    # 0.50 = au pire on touche la moitie des points.
    plancher_malus: Mapped[float] = mapped_column(Float, nullable=False, default=0.50)

    # Bonus cliff : multiplicateur supplementaire des seuil bonus franchi.
    # 0.50 = +50% points. Multiplicateur applique = 1.0 + bonus_pct.
    bonus_pct: Mapped[float] = mapped_column(Float, nullable=False, default=0.50)

    # --- Analyse "systematiquement en retard" (bump effort) ---
    # Y = clamp(K * interval, y_min, y_max) jours.
    bump_interval_factor_k: Mapped[float] = mapped_column(Float, nullable=False, default=3.0)
    bump_y_min_days: Mapped[int] = mapped_column(Integer, nullable=False, default=90)
    bump_y_max_days: Mapped[int] = mapped_column(Integer, nullable=False, default=1095)
    # Z : minimum de completions dans la fenetre Y pour declencher.
    bump_z_min: Mapped[int] = mapped_column(Integer, nullable=False, default=3)
    # X : fraction minimum de completions en zone bonus pour qualifier.
    bump_x_ratio: Mapped[float] = mapped_column(Float, nullable=False, default=0.70)
    # Grace period apres dispatch d'un bump prompt : grace_ratio * Y jours.
    bump_grace_ratio: Mapped[float] = mapped_column(Float, nullable=False, default=0.70)

    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )
