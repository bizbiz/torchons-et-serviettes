"""/sync must not store a recurrence type that no read path knows.

/sync bypasses Pydantic, so before this guard any `type` went straight to the
database and the task was silently inert (no due date, green forever).

PATHS by which a rule reaches `tasks.recurrence_rule`, listed before the cases:
  (1) /sync INSERT ............................................ COVERED
  (2) /sync UPDATE ............................................ COVERED
  (3) REST POST/PUT /tasks .... not covered here: Pydantic `RecurrenceRule`
      already refuses anything outside interval/monthly_variable/uncertain.
  (4) frequency vote consensus  not covered: writes interval/monthly_variable
      only, by construction.
  (5) rows stored BEFORE the conversion existed: migration d4ec241658ca,
      proven against the dev database, not by pytest.
"""

from __future__ import annotations

import uuid as uuid_lib
from datetime import datetime, timezone

import pytest


def _op(operation: str, row_id: str, data: dict) -> dict:
    return {
        "client_op_id": str(uuid_lib.uuid4()),
        "table": "tasks",
        "row_id": row_id,
        "operation": operation,
        "client_timestamp": datetime.now(timezone.utc).isoformat(),
        "data": data,
    }


async def _sync(client, headers, device, ops: list[dict]) -> dict:
    resp = await client.post(
        "/api/sync",
        headers=headers,
        json={"device_id": device, "last_server_version": 0, "changes": ops},
    )
    assert resp.status_code == 200, resp.text
    return resp.json()


@pytest.mark.asyncio
async def test_sync_insert_with_unknown_type_is_a_conflict(client, auth_headers, device_a):
    task_id = str(uuid_lib.uuid4())
    body = await _sync(client, auth_headers, device_a, [_op("INSERT", task_id, {
        "title": "unknown type", "assigned_to_ids": [],
        "recurrence_rule": {"type": "foo"},
    })])
    assert [c["row_id"] for c in body["conflicts"]] == [task_id], body
    assert body["conflicts"][0]["reason"] == "op_error"
    assert "unsupported recurrence type" in body["conflicts"][0]["detail"]
    resp = await client.get(f"/api/tasks/{task_id}", headers=auth_headers)
    assert resp.status_code == 404, "the task must not have been stored"


@pytest.mark.asyncio
async def test_sync_insert_dead_monthly_is_stored_as_calendar(client, auth_headers, device_a):
    task_id = str(uuid_lib.uuid4())
    body = await _sync(client, auth_headers, device_a, [_op("INSERT", task_id, {
        "title": "dead monthly", "assigned_to_ids": [],
        "recurrence_rule": {"type": "monthly", "day_of_month": 1},
    })])
    assert body["conflicts"] == [], body
    task = (await client.get(f"/api/tasks/{task_id}", headers=auth_headers)).json()
    assert task["recurrence_rule"]["type"] == "calendar"
    assert task["recurrence_rule"]["months"] == list(range(1, 13))


@pytest.mark.asyncio
async def test_sync_update_to_unknown_type_is_a_conflict_and_keeps_the_rule(
    client, auth_headers, device_a
):
    task_id = str(uuid_lib.uuid4())
    await _sync(client, auth_headers, device_a, [_op("INSERT", task_id, {
        "title": "update guard", "assigned_to_ids": [],
        "recurrence_rule": {"type": "interval", "every_n_days": 3},
    })])
    body = await _sync(client, auth_headers, device_a, [_op("UPDATE", task_id, {
        "recurrence_rule": {"type": "foo"},
    })])
    assert [c["row_id"] for c in body["conflicts"]] == [task_id], body
    task = (await client.get(f"/api/tasks/{task_id}", headers=auth_headers)).json()
    assert task["recurrence_rule"] == {"type": "interval", "every_n_days": 3}


@pytest.mark.asyncio
async def test_a_failing_op_does_not_undo_the_ops_before_it(client, auth_headers, device_a):
    """The safety net rolled back the WHOLE session: the valid op before the
    failing one was reported `applied` (so the client dropped it from its
    queue) and yet never stored."""
    good_id, bad_id = str(uuid_lib.uuid4()), str(uuid_lib.uuid4())
    body = await _sync(client, auth_headers, device_a, [
        _op("INSERT", good_id, {"title": "good op", "assigned_to_ids": [],
                                "recurrence_rule": {"type": "interval", "every_n_days": 3}}),
        _op("INSERT", bad_id, {"title": "bad op", "assigned_to_ids": [],
                               "recurrence_rule": {"type": "foo"}}),
    ])
    assert [a["server_row_id"] for a in body["applied"]] == [good_id], body
    assert [c["row_id"] for c in body["conflicts"]] == [bad_id], body
    resp = await client.get(f"/api/tasks/{good_id}", headers=auth_headers)
    assert resp.status_code == 200, "an op reported applied must be stored"
