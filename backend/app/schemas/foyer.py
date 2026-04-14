"""Schemas Pydantic pour les foyers."""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, Field


class FoyerCreate(BaseModel):
    name: str = Field(min_length=1, max_length=100)


class FoyerUpdate(BaseModel):
    name: str | None = Field(default=None, max_length=100)
    home_freeze_enabled: bool | None = None


class HomeFreezeStatus(BaseModel):
    """Retourne l'etat global du freeze + contexte pour afficher la tooltip
    adaptative sur le flag requires_home.
    """
    foyer_freeze_enabled: bool
    my_wifi_optin: bool
    my_seen_at_home: bool
    # Liste des display_name des autres users du foyer qui n'ont pas
    # opted-in WiFi ou jamais ete vus a la maison. Le freeze ne peut pas
    # fonctionner de maniere fiable tant qu'il reste des missing.
    missing_users: list[str]


class FoyerResponse(BaseModel):
    id: UUID
    name: str
    home_freeze_enabled: bool
    last_occupied_at: datetime | None
    created_at: datetime
    role: str  # role du user courant dans ce foyer (admin/member)
    is_current: bool  # true si c'est le foyer actif

    model_config = {"from_attributes": True}
