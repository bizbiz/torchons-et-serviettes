"""Schemas Pydantic pour les taches."""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, Field, model_validator

from app.models.enums import TaskTolerance


class RecurrenceRule(BaseModel):
    type: str  # interval, weekdays, monthly, custom, monthly_variable, uncertain
    every_n_days: int | None = None
    days: list[int] | None = None  # ISO weekday (1=lundi)
    day_of_month: int | None = None
    every_n_weeks: int | None = None
    day: int | None = None
    days_per_month: dict[str, int] | None = None

    @model_validator(mode="after")
    def _validate_uncertain(self) -> "RecurrenceRule":
        """Une recurrence 'uncertain' ne doit porter aucun parametre de cadence.
        La frequence est inferee cote backend a partir de l'historique."""
        if self.type == "uncertain":
            extras = {
                "every_n_days": self.every_n_days,
                "days": self.days,
                "day_of_month": self.day_of_month,
                "every_n_weeks": self.every_n_weeks,
                "day": self.day,
                "days_per_month": self.days_per_month,
            }
            set_fields = [k for k, v in extras.items() if v is not None]
            if set_fields:
                raise ValueError(
                    f"recurrence 'uncertain' ne doit pas porter de champs de cadence "
                    f"(trouves : {set_fields})"
                )
        return self


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
    inferred_interval_days: float | None = None
    weight: int
    tolerance: TaskTolerance
    is_active: bool
    sort_order: int
    created_at: datetime
    updated_at: datetime
