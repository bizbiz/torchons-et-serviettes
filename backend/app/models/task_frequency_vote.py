"""Modele vote de frequence — days_per_month (12 mois) par utilisateur (v0.27).

Forme canonique : un vote = 12 ints, un par mois. Un vote "tous les N jours"
(mode Simple cote UI) ecrit 12 fois la meme valeur. Un vote "Selon le mois"
(mode variable) ecrit des valeurs distinctes.

Le consensus (moyenne arrondie par mois) est stocke dans
`tasks.recurrence_rule` avec collapse-on-write :
- 12 valeurs egales -> {type:"interval", every_n_days: N}
- valeurs varyantes -> {type:"monthly_variable", days_per_month: {...}}
"""

import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, func
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base


class TaskFrequencyVote(Base):
    """Vote d'un utilisateur sur la frequence d'une tache, granularite mois.

    `days_per_month` : dict {"1": int, "2": int, ..., "12": int} — nombre
    de jours entre deux executions souhaite par l'user, par mois (cle =
    numero de mois en string). Toujours 12 entrees.

    Le consensus (moyenne par mois) est calcule par
    `app.services.frequency_vote.upsert_vote_and_sync_consensus`.
    """
    __tablename__ = "task_frequency_votes"

    task_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("tasks.id", ondelete="CASCADE"), primary_key=True
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    days_per_month: Mapped[dict] = mapped_column(JSONB, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )
