"""Schemas Pydantic pour les pieces (rooms)."""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, Field


class RoomCreate(BaseModel):
    name: str = Field(max_length=100)
    color: str = Field(default="#6B7280", max_length=7)
    icon: str | None = Field(default=None, max_length=50)
    emoji: str | None = Field(default=None, max_length=10)
    client_supplied_id: UUID | None = None
    client_op_id: UUID | None = None


class RoomUpdate(BaseModel):
    name: str | None = Field(default=None, max_length=100)
    color: str | None = Field(default=None, max_length=7)
    icon: str | None = Field(default=None, max_length=50)
    emoji: str | None = Field(default=None, max_length=10)


class RoomResponse(BaseModel):
    id: UUID
    name: str
    color: str
    icon: str | None
    emoji: str | None
    created_at: datetime

    model_config = {"from_attributes": True}
