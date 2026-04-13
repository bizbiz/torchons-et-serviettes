"""Schemas Pydantic pour les taches."""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, Field

from app.models.enums import TaskTolerance


class RecurrenceRule(BaseModel):
    type: str  # interval, weekdays, monthly, custom
    every_n_days: int | None = None
    days: list[int] | None = None  # ISO weekday (1=lundi)
    day_of_month: int | None = None
    every_n_weeks: int | None = None
    day: int | None = None


class TaskCreate(BaseModel):
    title: str = Field(max_length=300)
    description: str | None = None
    category_id: UUID | None = None
    room_id: UUID | None = None
    assigned_to_ids: list[UUID] = Field(min_length=1)
    recurrence_rule: RecurrenceRule | None = None
    weight: int = Field(default=1, ge=1, le=18)
    tolerance: TaskTolerance = TaskTolerance.NORMAL
    client_supplied_id: UUID | None = None
    client_op_id: UUID | None = None


class TaskUpdate(BaseModel):
    title: str | None = Field(default=None, max_length=300)
    description: str | None = None
    category_id: UUID | None = None
    room_id: UUID | None = None
    assigned_to_ids: list[UUID] | None = Field(default=None, min_length=1)
    recurrence_rule: RecurrenceRule | None = None
    weight: int | None = Field(default=None, ge=1, le=18)
    tolerance: TaskTolerance | None = None
    is_active: bool | None = None
    sort_order: int | None = None


class TaskResponse(BaseModel):
    id: UUID
    title: str
    description: str | None
    category_id: UUID | None
    room_id: UUID | None
    assigned_to_ids: list[UUID]
    recurrence_rule: dict | None
    weight: int
    tolerance: TaskTolerance
    is_active: bool
    sort_order: int
    created_at: datetime
    updated_at: datetime
