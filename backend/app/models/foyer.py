"""Modele foyer — container multi-tenant pour taches, pieces, categories, etc.

Un foyer represente un groupe d'utilisateurs partageant le meme environnement
(ex: un couple, une colloc). Toutes les donnees de tache sont scopees par
foyer via foreign key directe (tasks.foyer_id) ou transitivement (via tasks).

Relation N:N avec users via la table user_foyers — un user peut appartenir
a plusieurs foyers (ex: ma maison + la colloc d'amis).
"""

from sqlalchemy import Boolean, String
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
