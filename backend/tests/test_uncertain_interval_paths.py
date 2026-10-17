"""Every change to the completions of an `uncertain` task re-infers its interval.

`inferred_interval_days` is derived from the task's live completions. Until 2026-10-17 only
the REST complete, complete-collective, collective undo and presence-change paths recomputed
it: a solo completion queued offline arrives through /sync and left the interval stale.
Measured in production that day: 5 uncertain tasks with 2 or more completions, none at null,
all at the value of their last REST completion.

PATHS that add, remove or move a completion of a task (grep of `TaskCompletion(` and of
`deleted_at =` / `completed_at =` on completions), each a way for the invariant to break:
  (1) /sync INSERT ............................................... COVERED
  (2) /sync DELETE ............................................... COVERED
  (3) /sync UPDATE of completed_at ............................... COVERED
  (4) REST DELETE /tasks/{id}/completions/{cid} (undo) ........... COVERED
  (5) REST PUT /completions/{id} (date edit, stats.py) ........... COVERED
  (6) REST complete / complete-collective ........ covered by test_uncertain_rest_completion
  (7) collective undo, presence change ........... not covered: they already called the
      recompute before this change, and are untouched by it.
"""

from __future__ import annotations

import uuid

import pytest

from tests.conftest import DEVICE_A, TEST_USER_ID

DAY0 = "2026-03-01T09:00:00+00:00"
DAY10 = "2026-03-11T09:00:00+00:00"
DAY30 = "2026-03-31T09:00:00+00:00"


async def _uncertain_task(client, headers) -> str:
    resp = await client.post("/api/tasks", headers=headers, json={
        "title": "uncertain paths", "assigned_to_ids": [TEST_USER_ID],
        "recurrence_rule": {"type": "uncertain"}})
    assert resp.status_code == 201, resp.text
    return resp.json()["id"]


async def _interval(client, headers, task_id):
    task = (await client.get(f"/api/tasks/{task_id}", headers=headers)).json()
    return task["inferred_interval_days"]


async def _sync(client, headers, operation, row_id, data=None):
    op = {"client_op_id": str(uuid.uuid4()), "table": "task_completions",
          "operation": operation, "row_id": row_id, "data": data or {},
          "client_timestamp": "2026-04-01T09:00:00+00:00"}
    r = await client.post("/api/sync", headers=headers, json={
        "device_id": DEVICE_A, "last_server_version": 0, "changes": [op]})
    assert r.status_code == 200, r.text
    assert r.json()["conflicts"] == [], r.text


async def _sync_insert(client, headers, task_id, when) -> str:
    row_id = str(uuid.uuid4())
    await _sync(client, headers, "INSERT", row_id, {
        "task_id": task_id, "completed_by": TEST_USER_ID, "completed_at": when})
    return row_id


async def _rest_complete(client, headers, task_id, when) -> str:
    r = await client.post(f"/api/tasks/{task_id}/complete", headers=headers,
                          json={"completed_at": when})
    assert r.status_code == 201, r.text
    return r.json()["id"]


@pytest.mark.asyncio
async def test_sync_insert_infers_the_interval(client, auth_headers):
    task_id = await _uncertain_task(client, auth_headers)
    await _sync_insert(client, auth_headers, task_id, DAY0)
    await _sync_insert(client, auth_headers, task_id, DAY10)
    assert await _interval(client, auth_headers, task_id) == pytest.approx(10)


@pytest.mark.asyncio
async def test_sync_delete_reinfers_the_interval(client, auth_headers):
    task_id = await _uncertain_task(client, auth_headers)
    await _rest_complete(client, auth_headers, task_id, DAY0)
    await _rest_complete(client, auth_headers, task_id, DAY10)
    last = await _rest_complete(client, auth_headers, task_id, DAY30)
    with_three = await _interval(client, auth_headers, task_id)
    assert with_three != pytest.approx(10), with_three
    await _sync(client, auth_headers, "DELETE", last)
    assert await _interval(client, auth_headers, task_id) == pytest.approx(10)


@pytest.mark.asyncio
async def test_sync_update_of_the_date_reinfers_the_interval(client, auth_headers):
    task_id = await _uncertain_task(client, auth_headers)
    await _rest_complete(client, auth_headers, task_id, DAY0)
    second = await _rest_complete(client, auth_headers, task_id, DAY10)
    assert await _interval(client, auth_headers, task_id) == pytest.approx(10)
    await _sync(client, auth_headers, "UPDATE", second, {"completed_at": DAY30})
    assert await _interval(client, auth_headers, task_id) == pytest.approx(30)


@pytest.mark.asyncio
async def test_rest_undo_reinfers_the_interval(client, auth_headers):
    task_id = await _uncertain_task(client, auth_headers)
    await _rest_complete(client, auth_headers, task_id, DAY0)
    await _rest_complete(client, auth_headers, task_id, DAY10)
    last = await _rest_complete(client, auth_headers, task_id, DAY30)
    assert await _interval(client, auth_headers, task_id) != pytest.approx(10)
    r = await client.delete(f"/api/tasks/{task_id}/completions/{last}", headers=auth_headers)
    assert r.status_code == 200, r.text
    assert await _interval(client, auth_headers, task_id) == pytest.approx(10)


@pytest.mark.asyncio
async def test_rest_date_edit_reinfers_the_interval(client, auth_headers):
    task_id = await _uncertain_task(client, auth_headers)
    await _rest_complete(client, auth_headers, task_id, DAY0)
    second = await _rest_complete(client, auth_headers, task_id, DAY10)
    r = await client.put(f"/api/completions/{second}", headers=auth_headers,
                         json={"completed_at": DAY30})
    assert r.status_code == 200, r.text
    assert await _interval(client, auth_headers, task_id) == pytest.approx(30)
