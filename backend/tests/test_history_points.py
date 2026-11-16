"""History descriptions carry points only for a reader who sees points.

Invariant: the text "X completed: Y (+3 pts)" is written by the server, so the
app cannot hide the number inside it; the server leaves it out when the reader
does not see points (seen on prod 2026-11-16, v0.45.0: Motivation off, points
still in the profile's recent history).

PATHS:
  (1) motivation on, not hidden ............................ points shown  COVERED
  (2) motivation off for the household ..................... no points     COVERED
  (3) motivation on, reader hid them (user_settings) ....... no points     COVERED
  (4) GET /api/users/{id}/history follows the same rule .................. COVERED
Not covered: the collective template (same helper, `points_collective`).
"""

from __future__ import annotations

import pytest

from tests.module_helpers import House, add_completions, house, set_module


async def _descriptions(client, h: House, member: int = 0, path: str = "/api/history"):
    resp = await client.get(path, headers=h.headers(member))
    assert resp.status_code == 200, resp.text
    completions = [e for e in resp.json() if e["type"] == "completion"]
    assert completions, "the probe must read at least one completion"
    return [e["description"] for e in completions]


async def _hide_for(h: House, member: int) -> None:
    from app.database import async_session
    from app.models.user_settings import UserSettings

    async with async_session() as session:
        session.add(UserSettings(user_id=h.user_ids[member], gamification_enabled=False))
        await session.commit()


@pytest.mark.asyncio
async def test_points_shown_when_motivation_is_on(client):
    async with house() as h:
        await set_module(h, "motivation", True, updated_by=None)
        await add_completions(h, 1)
        assert all("pts" in d for d in await _descriptions(client, h))


@pytest.mark.asyncio
async def test_no_points_when_the_household_turned_motivation_off(client):
    async with house() as h:
        await set_module(h, "motivation", False, updated_by=1)
        await add_completions(h, 1)
        for path in ("/api/history", f"/api/users/{h.user_ids[0]}/history"):
            descriptions = await _descriptions(client, h, path=path)
            assert not any("pts" in d for d in descriptions), (path, descriptions)


@pytest.mark.asyncio
async def test_no_points_for_a_reader_who_hid_them(client):
    async with house() as h:
        await set_module(h, "motivation", True, updated_by=None)
        await add_completions(h, 1)
        await _hide_for(h, 0)
        assert not any("pts" in d for d in await _descriptions(client, h, member=0))
        # The other member did not hide them.
        assert all("pts" in d for d in await _descriptions(client, h, member=1))
