"""A refusal for users outside the household names them, and never shows an id.

The app shows the server's message as is (« Refusé par le serveur », readableSyncError). On
2026-11-02 ts-test-runner saw there « Utilisateurs hors du foyer : ['b25bdd0d-…'] ».

PATHS by which an outsider's id reaches the user, listed before the cases:
  (1) /sync, task assigned to a REAL user outside the household (assert_active_members) . COVERED
  (2) /sync, task assigned to an id no user carries ...................................... COVERED
  (3) REST « fait ensemble » with a participant outside the household ..................... COVERED
  non couvert : REST task create/update with outsiders, same assert_active_members as (1).
  non couvert : the English messages, same format_args, read by the same code.
"""

from __future__ import annotations

import re
import uuid as uuid_lib
from datetime import datetime, timezone

import pytest

UUID_RE = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-")


async def _outsider() -> tuple[str, str]:
    """A real user, member of no household, with a recognisable name."""
    from app.database import async_session
    from app.models.user import User

    uid = uuid_lib.uuid4()
    name = f"Hors-{uid.hex[:6]}"
    async with async_session() as session:
        session.add(User(id=uid, username=f"hors_{uid.hex[:12]}", display_name=name))
        await session.commit()
    return str(uid), name


async def _sync_detail(client, headers, device, assigned: str) -> tuple[str, str]:
    op = {
        "client_op_id": str(uuid_lib.uuid4()), "table": "tasks", "row_id": str(uuid_lib.uuid4()),
        "operation": "INSERT",
        "client_timestamp": datetime(2026, 1, 15, 12, tzinfo=timezone.utc).isoformat(),
        "data": {"title": "assigned to an outsider", "assigned_to_ids": [assigned]},
    }
    resp = await client.post(
        "/api/sync", headers=headers,
        json={"device_id": device, "last_server_version": 0, "changes": [op]},
    )
    assert resp.status_code == 200, resp.text
    conflict = resp.json()["conflicts"][0]
    return conflict["reason"], conflict["detail"]


@pytest.mark.asyncio
async def test_sync_names_a_real_outsider(client, auth_headers, device_a):
    uid, name = await _outsider()
    reason, detail = await _sync_detail(client, auth_headers, device_a, uid)
    assert reason == "rejected"
    assert name in detail, detail
    assert not UUID_RE.search(detail), detail


@pytest.mark.asyncio
async def test_sync_unknown_id_says_it_without_the_id(client, auth_headers, device_a):
    reason, detail = await _sync_detail(client, auth_headers, device_a, str(uuid_lib.uuid4()))
    assert reason == "rejected"  # still refused for good: the op can never apply
    assert "pas dans le foyer" in detail, detail
    assert not UUID_RE.search(detail), detail


@pytest.mark.asyncio
async def test_done_together_names_the_outsider(client, auth_headers):
    from tests.conftest import TEST_USER_ID

    uid, name = await _outsider()
    created = await client.post("/api/tasks", headers=auth_headers, json={
        "title": "together with an outsider", "assigned_to_ids": [TEST_USER_ID],
        "recurrence_rule": {"type": "interval", "every_n_days": 3},
    })
    assert created.status_code == 201, created.text
    resp = await client.post(
        f"/api/tasks/{created.json()['id']}/complete-collective", headers=auth_headers,
        json={"participant_user_ids": [TEST_USER_ID, uid]},
    )
    assert resp.status_code == 400, resp.text
    body = resp.text
    assert name in body, body
    assert not UUID_RE.search(resp.json().get("detail", "")), body
