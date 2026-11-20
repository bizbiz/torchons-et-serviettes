"""POST /api/sync -- stale-update rule (D2) and pull cursor (D10).

D2: an op is judged stale only against a change made by someone else since it was queued,
never against a write that came from the SAME device (its own earlier op in the queue).
D10: the returned `server_version` never goes past a change_log row that was not delivered.

Paths by which the invariants can be violated (scope of this file):
  stale rule, `_handle_task` UPDATE (the only /sync handler that reads `client_timestamp`;
  completions, delegations and settings never judge staleness):
    - own INSERT earlier in the same batch                      covered (create-then-edit)
    - own UPDATE earlier in the same batch / earlier push      covered (two edits)
    - foreign REST write (change_log.client_op_id NULL)         covered (stays stale / not stale)
    - foreign op of another device (processed_ops.device_id)    covered (stays stale)
    - own op sitting between a foreign write and the late op    covered (foreign not hidden)
    - non-logged writes bumping tasks.updated_at (completion recomputing
      `inferred_interval_days`): not covered, they are system writes, not edits.
    - same device_id used by two users: not covered, one device = one phone = one user.
  pull cursor, `sync()` tail:
    - more rows than PULL_LIMIT                                  covered (paging loop)
    - fewer rows than PULL_LIMIT                                 covered
    - rows of another household between ours: not covered, one test household; the
      household filter is in the SQL and unchanged.
    - a lower version committed after a higher one (MAX+1 numbering, concurrent
      transactions): not covered, needs two concurrent transactions; pre-existing.

Timestamps come from the DATABASE clock (`updated_at` / `changed_at` are `now()` of the
Postgres server, which is another machine than the one running pytest).
"""

from __future__ import annotations

import asyncio
import uuid
from datetime import datetime, timedelta

import pytest
from sqlalchemy import func, select

from app.database import async_session
from tests.conftest import TEST_USER_ID
from tests.test_sync import _db_max_version, _make_op, _sync_body


async def _db_now() -> datetime:
    async with async_session() as session:
        return (await session.execute(select(func.clock_timestamp()))).scalar()


async def _tick() -> datetime:
    """A database-clock instant strictly after everything written so far and before what follows."""
    await asyncio.sleep(0.05)
    now = await _db_now()
    await asyncio.sleep(0.05)
    return now


def _iso(dt: datetime) -> str:
    return dt.isoformat()


async def _title(client, auth_headers, task_id: str) -> str:
    r = await client.get(f"/api/tasks/{task_id}", headers=auth_headers)
    assert r.status_code == 200, r.text
    return r.json()["title"]


def _insert(row_id: str, ts: datetime, title: str = "created offline") -> dict:
    return _make_op(
        table="tasks", operation="INSERT", row_id=row_id, client_timestamp=_iso(ts),
        data={"title": title, "assigned_to_ids": [TEST_USER_ID]},
    )


def _update(row_id: str, ts: datetime, title: str) -> dict:
    return _make_op(
        table="tasks", operation="UPDATE", row_id=row_id, client_timestamp=_iso(ts),
        data={"title": title},
    )


@pytest.mark.asyncio
async def test_offline_create_then_edit_in_one_push_is_applied(client, auth_headers, device_a):
    """Create at T0, edit at T1, both pushed at T2 > T1: the edit must not be `stale_update`."""
    task_id = str(uuid.uuid4())
    t0 = await _tick()
    t1 = await _tick()
    r = await client.post(
        "/api/sync",
        json=_sync_body(device_a, [_insert(task_id, t0), _update(task_id, t1, "edited offline")]),
        headers=auth_headers,
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["conflicts"] == [], [c["reason"] for c in body["conflicts"]]
    assert len(body["applied"]) == 2
    assert await _title(client, auth_headers, task_id) == "edited offline"


@pytest.mark.asyncio
async def test_two_offline_edits_of_one_task_in_one_push_are_applied(client, auth_headers, device_a):
    """Two edits queued at T1 < T2, pushed together at T3: the second must not be stale."""
    task_id = str(uuid.uuid4())
    created = await client.post(
        "/api/sync", json=_sync_body(device_a, [_insert(task_id, await _db_now())]),
        headers=auth_headers,
    )
    assert created.json()["conflicts"] == []
    t1 = await _tick()
    t2 = await _tick()
    r = await client.post(
        "/api/sync",
        json=_sync_body(device_a, [_update(task_id, t1, "first edit"), _update(task_id, t2, "second edit")]),
        headers=auth_headers,
    )
    assert r.status_code == 200, r.text
    assert r.json()["conflicts"] == [], [c["reason"] for c in r.json()["conflicts"]]
    assert await _title(client, auth_headers, task_id) == "second edit"


@pytest.mark.asyncio
async def test_edit_queued_before_another_members_edit_stays_stale(client, auth_headers, device_a):
    """Genuine conflict: someone else (REST, no client_op_id) edited after my op was queued."""
    task_id = str(uuid.uuid4())
    await client.post(
        "/api/sync", json=_sync_body(device_a, [_insert(task_id, await _db_now())]),
        headers=auth_headers,
    )
    queued_at = await _tick()
    put = await client.put(f"/api/tasks/{task_id}", json={"title": "by someone else"}, headers=auth_headers)
    assert put.status_code == 200, put.text
    r = await client.post(
        "/api/sync", json=_sync_body(device_a, [_update(task_id, queued_at, "too late")]),
        headers=auth_headers,
    )
    conflicts = r.json()["conflicts"]
    assert [c["reason"] for c in conflicts] == ["stale_update"], r.text
    assert await _title(client, auth_headers, task_id) == "by someone else"


@pytest.mark.asyncio
async def test_edit_queued_before_another_devices_sync_edit_stays_stale(
    client, auth_headers, auth_headers_device_b, device_a, device_b,
):
    """Genuine conflict: device B pushed an edit (an op, with a processed_ops row of ANOTHER device)."""
    task_id = str(uuid.uuid4())
    await client.post(
        "/api/sync", json=_sync_body(device_a, [_insert(task_id, await _db_now())]),
        headers=auth_headers,
    )
    queued_at = await _tick()
    by_b = await client.post(
        "/api/sync", json=_sync_body(device_b, [_update(task_id, await _tick(), "edit by B")]),
        headers=auth_headers_device_b,
    )
    assert by_b.json()["conflicts"] == [], by_b.text
    r = await client.post(
        "/api/sync", json=_sync_body(device_a, [_update(task_id, queued_at, "A, queued earlier")]),
        headers=auth_headers,
    )
    assert [c["reason"] for c in r.json()["conflicts"]] == ["stale_update"], r.text
    assert await _title(client, auth_headers, task_id) == "edit by B"


@pytest.mark.asyncio
async def test_edit_queued_after_another_members_edit_is_applied(client, auth_headers, device_a):
    """An edit queued after the other member's change (the phone had seen it) is not a conflict."""
    task_id = str(uuid.uuid4())
    await client.post(
        "/api/sync", json=_sync_body(device_a, [_insert(task_id, await _db_now())]),
        headers=auth_headers,
    )
    await client.put(f"/api/tasks/{task_id}", json={"title": "by someone else"}, headers=auth_headers)
    queued_at = await _tick()
    r = await client.post(
        "/api/sync", json=_sync_body(device_a, [_update(task_id, queued_at, "after theirs")]),
        headers=auth_headers,
    )
    assert r.json()["conflicts"] == [], r.text
    assert await _title(client, auth_headers, task_id) == "after theirs"


@pytest.mark.asyncio
async def test_own_earlier_op_does_not_hide_a_foreign_edit_in_between(
    client, auth_headers, auth_headers_device_b, device_a, device_b,
):
    """A creates (T0), B edits (T1), A's edit queued at T0.5 is stale even though A wrote last."""
    task_id = str(uuid.uuid4())
    t0 = await _tick()
    await client.post("/api/sync", json=_sync_body(device_a, [_insert(task_id, t0)]), headers=auth_headers)
    queued_at = await _tick()
    await client.post(
        "/api/sync", json=_sync_body(device_b, [_update(task_id, await _tick(), "edit by B")]),
        headers=auth_headers_device_b,
    )
    # A's own earlier op is in the same batch as the late one: only B's change may count.
    first = _update(task_id, queued_at, "A first")
    second = _update(task_id, queued_at + timedelta(milliseconds=5), "A second")
    r = await client.post(
        "/api/sync", json=_sync_body(device_a, [first, second]), headers=auth_headers,
    )
    assert [c["reason"] for c in r.json()["conflicts"]] == ["stale_update", "stale_update"], r.text
    assert await _title(client, auth_headers, task_id) == "edit by B"


# ---------------------------------------------------------------- D10: pull cursor


async def _push_inserts(client, auth_headers, device_a, n: int) -> set[str]:
    ids = [str(uuid.uuid4()) for _ in range(n)]
    ts = await _db_now()
    r = await client.post(
        "/api/sync", json=_sync_body(device_a, [_insert(i, ts, f"cursor {k}") for k, i in enumerate(ids)]),
        headers=auth_headers,
    )
    assert r.json()["conflicts"] == [], r.text
    return set(ids)


@pytest.mark.asyncio
async def test_pull_cursor_stops_at_the_last_delivered_row_when_the_limit_cuts(
    client, auth_headers, auth_headers_device_b, device_a, device_b, monkeypatch,
):
    """More rows than the limit: each page's cursor is its last row; paging reaches every row."""
    import app.api.sync as sync_module

    baseline = await _db_max_version()
    mine = await _push_inserts(client, auth_headers, device_a, 5)

    monkeypatch.setattr(sync_module, "PULL_LIMIT", 2)
    cursor, seen, cut_pages = baseline, set(), 0
    for _ in range(20):
        page = (await client.post(
            "/api/sync", json=_sync_body(device_b, [], last_server_version=cursor),
            headers=auth_headers_device_b,
        )).json()
        changes = page["server_changes"]
        assert len(changes) <= 2
        if page["has_more"]:
            cut_pages += 1
            # The limit cut this page: the cursor is its last row, never past a row not delivered.
            assert page["server_version"] == changes[-1]["server_version"], page
        seen |= {c["row_id"] for c in changes}
        cursor = page["server_version"]
        if not page["has_more"]:
            break
    assert cut_pages == 2, f"5 rows by pages of 2 must cut twice, cut {cut_pages}"
    assert mine <= seen, f"skipped: {mine - seen}"


@pytest.mark.asyncio
async def test_pull_cursor_reaches_the_end_when_nothing_is_cut(
    client, auth_headers, auth_headers_device_b, device_a, device_b,
):
    """Under the limit: everything is delivered and the cursor is at (or past) the last row."""
    baseline = await _db_max_version()
    mine = await _push_inserts(client, auth_headers, device_a, 3)
    page = (await client.post(
        "/api/sync", json=_sync_body(device_b, [], last_server_version=baseline),
        headers=auth_headers_device_b,
    )).json()
    assert page["has_more"] is False
    assert mine <= {c["row_id"] for c in page["server_changes"]}
    assert page["server_version"] >= max(c["server_version"] for c in page["server_changes"])
