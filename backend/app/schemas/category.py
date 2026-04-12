"""Schemas Pydantic pour les categories."""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, Field


class CategoryCreate(BaseModel):
    name: str = Field(max_length=100)
    color: str = Field(default="#6B7280", max_length=7)
    icon: str | None = None
    requires_home: bool = False
    client_supplied_id: UUID | None = None
    client_op_id: UUID | None = None


class CategoryUpdate(BaseModel):
    name: str | None = Field(default=None, max_length=100)
    color: str | None = Field(default=None, max_length=7)
    icon: str | None = None
    sort_order: int | None = None
    requires_home: bool | None = None


class CategoryResponse(BaseModel):
    id: UUID
    name: str
    color: str
    icon: str | None
    sort_order: int
    requires_home: bool
    created_at: datetime

    model_config = {"from_attributes": True}
