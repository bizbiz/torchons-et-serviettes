"""A new account's first settings requests, sent together, must all succeed.

`user_settings` is created lazily, on the first read or write. Two requests arriving
together both found no row, both inserted one, and the second hit the primary key: a 500
on the first launch of every new account (beta log, 2026-10-12 14:02 UTC, just after a
pairing: two parallel GET /api/settings, one 200, one 500 on `user_settings_pkey`).

PATHS that create the row (grep of `UserSettings(` in app/), each a way to lose the race:
  (1) GET /api/settings ............................................. COVERED (GET x GET)
  (2) PUT /api/settings ............................................. COVERED (GET x PUT)
  (3) /sync UPDATE on user_settings ... COVERED (GET x /sync), but passed on the code
      before too: each /sync op runs in its own savepoint, so a collision there became a
      retried conflict rather than a 500.
Same race, found by these tests, on the `devices` row a new phone adopts on its first
authenticated call (utils/devices.py, `uq_devices_user_device`): every case also uses a
FRESH device_id, and the GET x PUT case failed on it once the settings race was fixed.
Each case uses a FRESH user, since the race exists only while the row is missing.
non couvert : `_load_or_create_user_foyer` (settings.py), same select-then-insert shape, but
the `user_foyers` row exists for any active member, whom get_current_user already requires.
"""

from __future__ import annotations

import asyncio
import uuid

import pytest

from tests.conftest import TEST_FOYER_ID


async def _fresh_member_headers() -> dict:
    from app.auth import create_token
    from app.database import async_session
    from app.models.user import User
    from app.models.user_foyer import UserFoyer

    user_id = uuid.uuid4()
    async with async_session() as session:
        session.add(User(id=user_id, username=f"race-{user_id.hex[:12]}", display_name="Race",
                         last_active_foyer_id=uuid.UUID(TEST_FOYER_ID)))
        await session.flush()
        session.add(UserFoyer(user_id=user_id, foyer_id=uuid.UUID(TEST_FOYER_ID), role="member"))
        await session.commit()
    token = create_token(str(user_id), str(uuid.uuid4()), TEST_FOYER_ID)
    return {"Authorization": f"Bearer {token}"}


def _sync_settings(headers):
    return {"device_id": str(uuid.uuid4()), "last_server_version": 0, "changes": [{
        "client_op_id": str(uuid.uuid4()), "table": "user_settings", "operation": "UPDATE",
        "row_id": str(uuid.uuid4()), "data": {"notif_hour": 8},
        "client_timestamp": "2026-04-01T09:00:00+00:00"}]}


@pytest.mark.asyncio
async def test_two_first_reads_together_both_succeed(client):
    headers = await _fresh_member_headers()
    a, b = await asyncio.gather(client.get("/api/settings", headers=headers),
                                client.get("/api/settings", headers=headers))
    assert (a.status_code, b.status_code) == (200, 200), (a.text[:200], b.text[:200])


@pytest.mark.asyncio
async def test_first_read_and_first_write_together_both_succeed(client):
    headers = await _fresh_member_headers()
    a, b = await asyncio.gather(
        client.get("/api/settings", headers=headers),
        client.put("/api/settings", headers=headers, json={"notif_hour": 8}))
    assert (a.status_code, b.status_code) == (200, 200), (a.text[:200], b.text[:200])
    after = await client.get("/api/settings", headers=headers)
    assert after.json()["notif_hour"] == 8, after.text


@pytest.mark.asyncio
async def test_first_read_and_first_sync_together_both_succeed(client):
    headers = await _fresh_member_headers()
    a, b = await asyncio.gather(
        client.get("/api/settings", headers=headers),
        client.post("/api/sync", headers=headers, json=_sync_settings(headers)))
    assert (a.status_code, b.status_code) == (200, 200), (a.text[:200], b.text[:200])
    assert b.json()["conflicts"] == [], b.text
    after = await client.get("/api/settings", headers=headers)
    assert after.json()["notif_hour"] == 8, after.text
