"""Schemas Pydantic pour le sync offline (POST /api/sync)."""

from __future__ import annotations

from datetime import datetime
from typing import Literal
from uuid import UUID

from pydantic import BaseModel


class SyncOperation(BaseModel):
    """Une operation effectuee offline par le client."""
    client_op_id: UUID
    table: Literal["tasks", "categories", "task_completions", "user_settings"]
    operation: Literal["INSERT", "UPDATE", "DELETE"]
    row_id: UUID
    data: dict | None = None
    client_timestamp: datetime


class SyncRequest(BaseModel):
    device_id: UUID
    last_server_version: int = 0
    changes: list[SyncOperation] = []


class SyncAppliedOp(BaseModel):
    client_op_id: UUID
    server_row_id: UUID
    new_version: int


class SyncConflict(BaseModel):
    client_op_id: UUID
    table: str
    row_id: UUID
    server_data: dict | None = None
    reason: Literal["stale_update", "deleted", "unique_violation", "not_found"]


class ChangeLogEntry(BaseModel):
    server_version: int
    table: str
    row_id: UUID
    operation: str
    data: dict | None
    changed_at: datetime


class SyncResponse(BaseModel):
    server_version: int
    applied: list[SyncAppliedOp] = []
    conflicts: list[SyncConflict] = []
    server_changes: list[ChangeLogEntry] = []
