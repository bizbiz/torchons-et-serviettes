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


class FoyerResponse(BaseModel):
    id: UUID
    name: str
    home_freeze_enabled: bool
    created_at: datetime
    role: str  # role du user courant dans ce foyer (admin/member)
    is_current: bool  # true si c'est le foyer actif
    # v0.30 — entitlement Premium par foyer (plomberie, paywall non actif).
    is_premium: bool = False
    premium_until: datetime | None = None

    model_config = {"from_attributes": True}
