"""Modele contexte — remplace Category ET Room (chantier contextes).

Un contexte est ce qui EXPLIQUE qu'une tache existe : une piece, un animal, un
exterieur, une situation de vie, ou un genre de travail. Les deux anciennes
tables etaient deja quasi identiques — meme foyer_id, name, color, icon,
template_key ; elles ne differaient que d'un champ chacune (`requires_home`
pour l'une, `emoji` pour l'autre). Les fusionner ne perd rien parce qu'une
tache peut desormais en declarer PLUSIEURS, la ou elle portait exactement une
piece et exactement une categorie.

DEUX DRAPEAUX, PAS UN. `requires_home` repondait a deux questions distinctes
avec une seule valeur, et se trompait forcement sur l'une des deux :

    « faut-il etre sur place ? »        -> masquage hors WiFi maison
    « le besoin monte-t-il sans nous ? » -> gel pendant les absences

Le jardin et le chat le prouvaient en sens inverse : il faut etre la pour
tondre (masquage juste) mais l'herbe pousse quand meme (gel faux) ; il faut
etre la pour la litiere (masquage faux, il etait desactive) mais rien ne
s'accumule si le chat part en pension (gel juste). Aucune valeur unique ne
satisfaisait les deux.
"""

import uuid
from datetime import datetime

from sqlalchemy import (
    Boolean, DateTime, ForeignKey, Index, Integer, String, func, text,
)
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, UUIDMixin


class Context(Base, UUIDMixin):
    """Un contexte du foyer : piece, animal, exterieur, situation, genre."""

    __tablename__ = "contexts"

    foyer_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("foyers.id", ondelete="CASCADE"),
        nullable=False,
    )
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    color: Mapped[str] = mapped_column(String(7), default="#6B7280")
    icon: Mapped[str | None] = mapped_column(String(50), nullable=True)
    emoji: Mapped[str | None] = mapped_column(String(10), nullable=True)
    sort_order: Mapped[int] = mapped_column(Integer, default=0)

    #: Faut-il etre sur place ? Pilote le masquage hors WiFi maison.
    requires_presence: Mapped[bool] = mapped_column(
        Boolean, default=False, server_default="false", nullable=False
    )
    #: Le besoin monte-t-il pendant une absence ? Pilote le gel. Vrai = on ne
    #: gele PAS (la pelouse pousse, le calendrier des impots tourne).
    accrues_when_away: Mapped[bool] = mapped_column(
        Boolean, default=False, server_default="false", nullable=False
    )

    #: Cle du catalogue dont ce contexte est issu, quand il vient d'un import.
    template_key: Mapped[str | None] = mapped_column(String(50), nullable=True)

    #: Incrementee a chaque ecriture. Toutes les entites synchronisees en
    #: portent une : sans elle, deux telephones qui renomment le meme contexte
    #: hors ligne se recouvrent sans que rien ne le signale.
    version: Mapped[int] = mapped_column(Integer, default=1, server_default="1",
                                         nullable=False)

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now(),
        nullable=False,
    )
    deleted_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )

    #: Unicite du nom PARMI LES VIVANTS. Une unique simple interdirait de
    #: recreer un contexte supprime, dont la ligne reste en base.
    __table_args__ = (
        Index("uq_contexts_name_foyer", "foyer_id", "name", unique=True,
              postgresql_where=text("deleted_at IS NULL")),
    )


class TaskContext(Base):
    """Rattachement d'une tache a un contexte.

    Table de liaison plutot qu'une colonne : une tache en porte plusieurs, et
    `position` retient lequel est le principal — celui qui decide de
    l'affichage. Sans l'ordre, « Nettoyer la litiere » n'aurait aucune raison
    de s'afficher sous Chat plutot que sous Salon.
    """

    __tablename__ = "task_contexts"

    task_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("tasks.id", ondelete="CASCADE"),
        primary_key=True,
    )
    context_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("contexts.id", ondelete="CASCADE"),
        primary_key=True,
    )
    #: 0 = contexte principal. Les suivants servent au filtrage et a la
    #: resolution des drapeaux, pas a l'affichage.
    position: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
