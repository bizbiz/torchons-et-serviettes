"""/sync tells a refusal no replay can lift (`rejected`) from one that may pass later.

The client drops a `rejected` op and says so; it replays an `op_error` one. Until
2026-10-17 both were `op_error`, and a refused op stayed queued forever.

PATHS by which an op ends in a refusal, listed before the cases:
  (1) a member outside the household (assert_active_members) .... COVERED, rejected
  (2) task values out of bounds (TaskUpdate, Pydantic) ............ COVERED, rejected
  (3) unknown recurrence type (server older than the app) ......... COVERED, op_error
  (4) operation a handler does not support ........................ COVERED, op_error
  (5) unknown table or operation .................................. not covered: the
      SyncOperation Literals refuse the whole batch with a 422 before any handler.
  (6) an unexpected exception (driver, database) .................. not covered: no
      error to provoke from a test; it is neither ValidationError nor a listed code.
"""

from __future__ import annotations

import uuid as uuid_lib
from datetime import datetime, timezone

import pytest


def _op(table: str, operation: str, row_id: str, data: dict) -> dict:
    return {
        "client_op_id": str(uuid_lib.uuid4()),
        "table": table,
        "row_id": row_id,
        "operation": operation,
        # Fixed: the refusal does not depend on when the op was queued.
        "client_timestamp": datetime(2026, 1, 15, 12, tzinfo=timezone.utc).isoformat(),
        "data": data,
    }


async def _reason(client, headers, device, op: dict) -> str:
    resp = await client.post(
        "/api/sync",
        headers=headers,
        json={"device_id": device, "last_server_version": 0, "changes": [op]},
    )
    assert resp.status_code == 200, resp.text
    conflicts = resp.json()["conflicts"]
    assert len(conflicts) == 1, resp.json()
    return conflicts[0]["reason"]


@pytest.mark.asyncio
async def test_member_outside_household_is_rejected(client, auth_headers, device_a):
    op = _op("tasks", "INSERT", str(uuid_lib.uuid4()), {
        "title": "assigned to a stranger", "assigned_to_ids": [str(uuid_lib.uuid4())],
    })
    assert await _reason(client, auth_headers, device_a, op) == "rejected"


@pytest.mark.asyncio
async def test_out_of_bounds_weight_is_rejected(client, auth_headers, device_a):
    op = _op("tasks", "INSERT", str(uuid_lib.uuid4()), {
        "title": "too heavy", "weight": 999, "assigned_to_ids": [],
    })
    assert await _reason(client, auth_headers, device_a, op) == "rejected"


@pytest.mark.asyncio
async def test_unknown_recurrence_type_stays_replayable(client, auth_headers, device_a):
    op = _op("tasks", "INSERT", str(uuid_lib.uuid4()), {
        "title": "newer type", "assigned_to_ids": [], "recurrence_rule": {"type": "foo"},
    })
    assert await _reason(client, auth_headers, device_a, op) == "op_error"


@pytest.mark.asyncio
async def test_unsupported_operation_stays_replayable(client, auth_headers, device_a):
    op = _op("user_settings", "DELETE", str(uuid_lib.uuid4()), {})
    assert await _reason(client, auth_headers, device_a, op) == "op_error"
