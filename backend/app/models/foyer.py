"""Modele foyer — container multi-tenant pour taches, pieces, categories, etc.

Un foyer represente un groupe d'utilisateurs partageant le meme environnement
(ex: un couple, une colloc). Toutes les donnees de tache sont scopees par
foyer via foreign key directe (tasks.foyer_id) ou transitivement (via tasks).

Relation N:N avec users via la table user_foyers — un user peut appartenir
a plusieurs foyers (ex: ma maison + la colloc d'amis).
"""

from datetime import datetime

from sqlalchemy import Boolean, DateTime, String
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, UUIDMixin, TimestampMixin, SoftDeleteMixin


class Foyer(Base, UUIDMixin, TimestampMixin, SoftDeleteMixin):
    __tablename__ = "foyers"

    name: Mapped[str] = mapped_column(String(100), nullable=False)

    # Code d'invitation pour rejoindre via QR (Feature 2 futur).
    # Genere a la demande via un bouton "Inviter quelqu'un" en Settings.
    invite_code: Mapped[str | None] = mapped_column(String(16), unique=True, nullable=True)

    # Toggle de la home presence freeze feature (v0.7.1).
    # False par defaut — le foyer doit explicitement l'activer.
    home_freeze_enabled: Mapped[bool] = mapped_column(Boolean, default=False)

    # Derniere fois ou au moins un user du foyer etait a la maison.
    # Mis a jour par POST /api/presence. Utilise pour calculer l'absence
    # et appliquer le freeze sur les taches requires_home.
    last_occupied_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
