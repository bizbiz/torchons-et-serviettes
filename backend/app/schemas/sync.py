"""Schemas Pydantic pour le sync offline (POST /api/sync)."""

from __future__ import annotations

from datetime import datetime
from typing import Literal
from uuid import UUID

from pydantic import BaseModel


class SyncOperation(BaseModel):
    """Une operation effectuee offline par le client."""
    client_op_id: UUID
    #: `categories` a disparu avec la bascule contextes : les contextes se
    #: gerent via leur CRUD REST, pas via la file offline.
    table: Literal["tasks", "task_completions", "user_settings"]
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
    # `op_error` = the safety net of POST /sync (an unexpected error on ONE op).
    # It was missing from this Literal from 2026-05-18 (036d084) to 2026-09-30:
    # building the conflict itself raised, the whole /sync answered 500, and the
    # client retried the same batch forever — the exact blockage the net exists
    # to prevent. The Android side reads `reason` as a plain String.
    reason: Literal["stale_update", "deleted", "unique_violation", "not_found", "op_error"]
    #: Human-readable cause for `op_error`, kept out of `reason` so that the
    #: latter stays an enum. Ignored by clients that do not know it.
    detail: str | None = None


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
