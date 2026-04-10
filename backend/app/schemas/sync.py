"""Schemas Pydantic pour le sync offline."""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from pydantic import BaseModel


class SyncChange(BaseModel):
    table: str
    row_id: UUID
    operation: str  # INSERT, UPDATE, DELETE
    data: dict
    client_timestamp: datetime


class SyncRequest(BaseModel):
    client_id: UUID
    last_server_version: int = 0
    changes: list[SyncChange] = []


class SyncConflict(BaseModel):
    table: str
    row_id: UUID
    client_data: dict
    server_data: dict
    resolution: str  # "server_wins", "client_wins"


class SyncResponse(BaseModel):
    server_version: int
    changes: list[dict]
    conflicts: list[SyncConflict] = []
