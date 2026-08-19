"""Pydantic schemas for contexts (replacing rooms and categories).

The two flags are exposed and editable: they answer two different questions,
and no single value satisfies both (see `docs/contexts.md`). Defaults are
deliberately the safe ones — a hand-made context requires presence and does
not accrue, which reproduces the previous behaviour of a room.
"""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, Field


class ContextCreate(BaseModel):
    name: str = Field(max_length=100)
    color: str = Field(default="#6B7280", max_length=7)
    icon: str | None = Field(default=None, max_length=50)
    emoji: str | None = Field(default=None, max_length=10)
    #: Must you be on site? Drives masking off home WiFi.
    requires_presence: bool = True
    #: Does the need grow while nobody is home? Drives absence freezing.
    accrues_when_away: bool = False
    sort_order: int = 0
    client_supplied_id: UUID | None = None
    client_op_id: UUID | None = None


class ContextUpdate(BaseModel):
    name: str | None = Field(default=None, max_length=100)
    color: str | None = Field(default=None, max_length=7)
    icon: str | None = Field(default=None, max_length=50)
    emoji: str | None = Field(default=None, max_length=10)
    requires_presence: bool | None = None
    accrues_when_away: bool | None = None
    sort_order: int | None = None


class ContextResponse(BaseModel):
    id: UUID
    name: str
    color: str
    icon: str | None
    emoji: str | None
    requires_presence: bool
    accrues_when_away: bool
    sort_order: int
    template_key: str | None
    version: int
    created_at: datetime

    model_config = {"from_attributes": True}


class TaskContextRef(BaseModel):
    """A context as seen from a task: enough to render it, plus its rank.

    `position` 0 is the primary context — the one a task is displayed under.
    """

    id: UUID
    name: str
    color: str
    emoji: str | None
    position: int
