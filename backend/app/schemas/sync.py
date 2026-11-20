"""Schemas Pydantic pour le sync offline (POST /api/sync)."""

from __future__ import annotations

from datetime import datetime
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, Field


class SyncOperation(BaseModel):
    """Une operation effectuee offline par le client."""
    client_op_id: UUID
    #: `categories` a disparu avec la bascule contextes : les contextes se
    #: gerent via leur CRUD REST, pas via la file offline.
    #: Known values: tasks, task_completions, user_settings; INSERT, UPDATE, DELETE.
    #: Plain strings on purpose (2026-11-03): with Literals, ONE op of an app newer than
    #: the server refused the whole batch with a 422, valid ops included, and the queue
    #: stayed blocked. An unknown value now fails that op alone, as a replayable
    #: `op_error` (api/sync.py, SYNC_UNKNOWN_TABLE / SYNC_UNKNOWN_OPERATION).
    table: str = Field(max_length=50)
    operation: str = Field(max_length=10)
    row_id: UUID
    data: dict | None = None
    client_timestamp: datetime
    #: Household the op was queued in. A client that switched household keeps
    #: ops queued under the previous one, and replays them under a token that
    #: names the new one: without this field a task created offline in A was
    #: created in B. Absent (older apps, ops queued before the upgrade): the
    #: token's household, as before.
    foyer_id: UUID | None = None


class SyncRequest(BaseModel):
    device_id: UUID
    last_server_version: int = 0
    # Bound on a hostile payload. The app sends batches of 100 (SyncWorker,
    # SYNC_BATCH_SIZE); older apps send their whole queue at once, which stays
    # far below 2000 in a household's offline life. A request above it is refused
    # (422) -- and an older app would replay it forever, hence the margin.
    changes: list[SyncOperation] = Field(default=[], max_length=2000)


class SyncAppliedOp(BaseModel):
    client_op_id: UUID
    server_row_id: UUID
    new_version: int
    #: True when the handler wrote nothing (a duplicate completion dropped by
    #: services/completion_guard.py, a row gone or hidden): the op is acknowledged so the
    #: queue moves on, but `server_row_id` (= the op's own row id) names no server row.
    #: The client must not keep a local row for it. Additive: older apps ignore it.
    dropped: bool = False


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
    # `out_of_scope` = the op can never apply (household left, row gone): the
    # client drops it instead of replaying it forever.
    # `rejected` = a refusal no replay can lift (sync.py `DEFINITIVE_REFUSALS`):
    # the client drops it and tells the user. Older clients keep it queued.
    reason: Literal[
        "stale_update", "deleted", "unique_violation", "not_found", "op_error", "out_of_scope",
        "rejected",
    ]
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
    #: Pull cursor. When `has_more` is true it is the version of the last row scanned, not
    #: the latest on the server: pull again from it.
    server_version: int
    #: True when the pull stopped at its row limit and more changes wait. Additive: an older
    #: app ignores it and catches up one page per sync instead of skipping them.
    has_more: bool = False
    applied: list[SyncAppliedOp] = []
    conflicts: list[SyncConflict] = []
    server_changes: list[ChangeLogEntry] = []
