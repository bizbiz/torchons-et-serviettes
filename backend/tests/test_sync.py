"""Tests pour POST /api/sync — idempotence, conflits, pull."""

from __future__ import annotations

import uuid
from datetime import datetime, timezone

import pytest

from tests.conftest import USER_ID_QUENTIN


def _make_op(
    table: str = "tasks",
    operation: str = "INSERT",
    row_id: str | None = None,
    data: dict | None = None,
    client_op_id: str | None = None,
    client_timestamp: str | None = None,
) -> dict:
    return {
        "client_op_id": client_op_id or str(uuid.uuid4()),
        "table": table,
        "operation": operation,
        "row_id": row_id or str(uuid.uuid4()),
        "data": data,
        "client_timestamp": client_timestamp or datetime.now(timezone.utc).isoformat(),
    }


def _sync_body(device_id: str, changes: list[dict], last_server_version: int = 0) -> dict:
    return {
        "device_id": device_id,
        "last_server_version": last_server_version,
        "changes": changes,
    }


# ---------------------------------------------------------------
# Test 1 : INSERT idempotent — meme client_op_id deux fois
# ---------------------------------------------------------------
@pytest.mark.asyncio
async def test_sync_insert_task_idempotent(client, auth_headers, device_a):
    """Envoyer la meme op INSERT deux fois ne doit pas creer de doublon."""
    row_id = str(uuid.uuid4())
    op_id = str(uuid.uuid4())
    op = _make_op(
        table="tasks",
        operation="INSERT",
        row_id=row_id,
        client_op_id=op_id,
        data={
            "title": "Test idempotent",
            "assigned_to_ids": [USER_ID_QUENTIN],
        },
    )

    # Premier appel
    r1 = await client.post("/api/sync", json=_sync_body(device_a, [op]), headers=auth_headers)
    assert r1.status_code == 200
    body1 = r1.json()
    assert len(body1["applied"]) == 1
    assert len(body1["conflicts"]) == 0
    assert body1["applied"][0]["server_row_id"] == row_id

    # Deuxieme appel (replay)
    r2 = await client.post("/api/sync", json=_sync_body(device_a, [op]), headers=auth_headers)
    assert r2.status_code == 200
    body2 = r2.json()
    assert len(body2["applied"]) == 1
    assert body2["applied"][0]["server_row_id"] == row_id

    # Cleanup
    cleanup_op = _make_op(table="tasks", operation="DELETE", row_id=row_id)
    await client.post("/api/sync", json=_sync_body(device_a, [cleanup_op]), headers=auth_headers)


# ---------------------------------------------------------------
# Test 2 : INSERT avec client_supplied_id (row_id choisi par le client)
# ---------------------------------------------------------------
@pytest.mark.asyncio
async def test_sync_insert_with_client_supplied_id(client, auth_headers, device_a):
    """Le row_id fourni par le client doit etre utilise comme PK."""
    chosen_id = str(uuid.uuid4())
    op = _make_op(
        table="tasks",
        operation="INSERT",
        row_id=chosen_id,
        data={
            "title": "Task avec ID client",
            "assigned_to_ids": [USER_ID_QUENTIN],
        },
    )

    r = await client.post("/api/sync", json=_sync_body(device_a, [op]), headers=auth_headers)
    assert r.status_code == 200
    body = r.json()
    assert body["applied"][0]["server_row_id"] == chosen_id

    # Verifier que la tache existe bien via GET /api/tasks
    tasks_r = await client.get("/api/tasks", headers=auth_headers)
    assert tasks_r.status_code == 200
    ids = [t["id"] for t in tasks_r.json()]
    assert chosen_id in ids

    # Cleanup
    cleanup_op = _make_op(table="tasks", operation="DELETE", row_id=chosen_id)
    await client.post("/api/sync", json=_sync_body(device_a, [cleanup_op]), headers=auth_headers)


# ---------------------------------------------------------------
# Test 3 : UPDATE stale → conflit
# ---------------------------------------------------------------
@pytest.mark.asyncio
async def test_sync_update_stale_conflict(client, auth_headers, device_a):
    """UPDATE avec client_timestamp < updated_at serveur doit retourner un conflit."""
    row_id = str(uuid.uuid4())
    # Creer la tache
    insert_op = _make_op(
        table="tasks",
        operation="INSERT",
        row_id=row_id,
        data={
            "title": "Stale test",
            "assigned_to_ids": [USER_ID_QUENTIN],
        },
    )
    await client.post("/api/sync", json=_sync_body(device_a, [insert_op]), headers=auth_headers)

    # Modifier cote serveur via PUT direct (simule un autre client)
    await client.put(
        f"/api/tasks/{row_id}",
        json={"title": "Modified server side"},
        headers=auth_headers,
    )

    # Tenter un UPDATE avec un timestamp passe (avant la modif serveur)
    stale_op = _make_op(
        table="tasks",
        operation="UPDATE",
        row_id=row_id,
        data={"title": "Stale client update"},
        client_timestamp="2020-01-01T00:00:00Z",
    )
    r = await client.post("/api/sync", json=_sync_body(device_a, [stale_op]), headers=auth_headers)
    assert r.status_code == 200
    body = r.json()
    assert len(body["conflicts"]) == 1
    assert body["conflicts"][0]["reason"] == "stale_update"
    assert body["conflicts"][0]["row_id"] == row_id

    # Cleanup
    cleanup_op = _make_op(table="tasks", operation="DELETE", row_id=row_id)
    await client.post("/api/sync", json=_sync_body(device_a, [cleanup_op]), headers=auth_headers)


# ---------------------------------------------------------------
# Test 4 : Pull exclut les ops du meme device
# ---------------------------------------------------------------
@pytest.mark.asyncio
async def test_sync_pull_excludes_own_device(client, auth_headers, auth_headers_device_b, device_a, device_b):
    """Device A push une op, puis pull → ne recoit pas son propre change.
    Device B pull → recoit le change de A."""
    row_id = str(uuid.uuid4())
    op = _make_op(
        table="tasks",
        operation="INSERT",
        row_id=row_id,
        data={
            "title": "Device filter test",
            "assigned_to_ids": [USER_ID_QUENTIN],
        },
    )

    # Device A push
    r1 = await client.post("/api/sync", json=_sync_body(device_a, [op]), headers=auth_headers)
    assert r1.status_code == 200
    cursor_before = r1.json()["server_version"]

    # Device A pull depuis avant l'op — ne doit PAS recevoir son propre change
    r_a = await client.post(
        "/api/sync",
        json=_sync_body(device_a, [], last_server_version=cursor_before - 1),
        headers=auth_headers,
    )
    assert r_a.status_code == 200
    own_changes = [c for c in r_a.json()["server_changes"] if c["row_id"] == row_id]
    assert len(own_changes) == 0

    # Device B pull depuis avant l'op — DOIT recevoir le change de A
    r_b = await client.post(
        "/api/sync",
        json=_sync_body(device_b, [], last_server_version=cursor_before - 1),
        headers=auth_headers_device_b,
    )
    assert r_b.status_code == 200
    other_changes = [c for c in r_b.json()["server_changes"] if c["row_id"] == row_id]
    assert len(other_changes) >= 1

    # Cleanup
    cleanup_op = _make_op(table="tasks", operation="DELETE", row_id=row_id)
    await client.post("/api/sync", json=_sync_body(device_a, [cleanup_op]), headers=auth_headers)


# ---------------------------------------------------------------
# Test 5 : DELETE puis pull confirme le delete
# ---------------------------------------------------------------
@pytest.mark.asyncio
async def test_sync_delete_visible_in_pull(client, auth_headers, auth_headers_device_b, device_a, device_b):
    """Device A cree + supprime une tache. Device B pull → voit le DELETE dans server_changes."""
    row_id = str(uuid.uuid4())

    # INSERT + flush
    insert_op = _make_op(
        table="tasks",
        operation="INSERT",
        row_id=row_id,
        data={
            "title": "To be deleted",
            "assigned_to_ids": [USER_ID_QUENTIN],
        },
    )
    r1 = await client.post("/api/sync", json=_sync_body(device_a, [insert_op]), headers=auth_headers)
    cursor_before = r1.json()["server_version"] - 1

    # DELETE
    delete_op = _make_op(table="tasks", operation="DELETE", row_id=row_id)
    await client.post("/api/sync", json=_sync_body(device_a, [delete_op]), headers=auth_headers)

    # Device B pull — doit voir INSERT + DELETE
    r_b = await client.post(
        "/api/sync",
        json=_sync_body(device_b, [], last_server_version=cursor_before),
        headers=auth_headers_device_b,
    )
    assert r_b.status_code == 200
    changes_for_row = [c for c in r_b.json()["server_changes"] if c["row_id"] == row_id]
    ops = [c["operation"] for c in changes_for_row]
    assert "INSERT" in ops
    assert "DELETE" in ops
