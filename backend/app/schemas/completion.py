"""Schemas Pydantic pour les completions de taches."""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from pydantic import BaseModel


class CompletionCreate(BaseModel):
    completed_by: UUID | None = None  # NULL = utilisateur courant
    completed_at: datetime | None = None  # NULL = maintenant, sinon date historique
    notes: str | None = None
    client_supplied_id: UUID | None = None
    client_op_id: UUID | None = None


class CompletionResponse(BaseModel):
    id: UUID
    task_id: UUID
    completed_by: UUID
    completed_at: datetime
    points_earned: int
    notes: str | None

    model_config = {"from_attributes": True}
