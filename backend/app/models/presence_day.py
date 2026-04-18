"""Modele presence_days — historique jour par jour de la presence au foyer.

Chaque ligne represente un jour calendaire (timezone Europe/Paris) avec un
statut confirme : present ou absent. Les jours sans ligne sont "incertains"
et doivent etre resolus manuellement par l'utilisateur via le prompt
RESOLVE_PRESENCE.

Sources :
- auto_wifi : le client a detecte le WiFi maison au lancement de l'app
  (POST /api/presence/checkin) — signal positif fiable.
- manual : l'utilisateur a resolu le jour via le calendrier interactif.

Note : le PresenceWorker en arriere-plan a ete supprime car les restrictions
Android recentes (Doze, background limits) empechent son execution fiable.
"""

import uuid
from datetime import date, datetime

from sqlalchemy import Date, DateTime, ForeignKey, String, func
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, UUIDMixin


class PresenceDay(Base, UUIDMixin):
    __tablename__ = "presence_days"

    foyer_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("foyers.id", ondelete="CASCADE"),
        nullable=False,
    )
    date: Mapped[date] = mapped_column(Date, nullable=False)
    # confirmed_present / confirmed_absent
    status: Mapped[str] = mapped_column(String(20), nullable=False)
    # auto_wifi / manual
    source: Mapped[str] = mapped_column(String(20), nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
