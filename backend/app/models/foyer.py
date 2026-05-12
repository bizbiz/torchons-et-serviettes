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
    # NOTE v0.30 : remplace par la table pairing_tokens (ephemere). Champ
    # conserve temporairement pour compat, sera retire apres migration des
    # quelques foyers existants.
    invite_code: Mapped[str | None] = mapped_column(String(16), unique=True, nullable=True)

    # Toggle de la home presence freeze feature (v0.7.1).
    # False par defaut — le foyer doit explicitement l'activer.
    home_freeze_enabled: Mapped[bool] = mapped_column(Boolean, default=False)

    # v0.32 : toggle gamification du foyer (points, bonus, stats, etc.).
    # Quand False, le front masque tout l'UI gamif pour les taches de ce
    # foyer pour TOUS ses membres. Combinaison OR avec
    # UserSettings.gamification_enabled cote frontend.
    gamification_enabled: Mapped[bool] = mapped_column(
        Boolean, default=True, server_default="true", nullable=False
    )

    # v0.30 : entitlement Premium par foyer. Pas de paywall actif a ce stade
    # (plomberie pour la v2 post-launch). Tous les members heritent.
    is_premium: Mapped[bool] = mapped_column(
        Boolean, default=False, server_default="false", nullable=False
    )
    premium_until: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )

    # v0.30 : foyer fantome (>90j sans activite, tous members partis). Mis
    # par job daily, purge complete apres 365j supplementaires.
    archived_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
