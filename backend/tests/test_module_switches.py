"""Foyer switches: the legacy gamification route and "who changed it".

Invariant: every switch the app offers for a household decision writes the state
the app reads (the `motivation` module), and the other members can be told who
changed a household module.

PATHS:
  (1) PUT /api/foyers/{id}/gamification writes the motivation module ...... COVERED
      (seen on prod 2026-11-16: it wrote the legacy column only, a no-op)
  (2) the legacy route keeps the dependency guard (bonus/malus) ........... COVERED
  (3) changed_by_name / changed_at: set for another member's change ....... COVERED
  (4) not set: the actor's own change, an unsigned (backfill) row,
      a personal module ................................................... COVERED
Not covered: the Android card that reads (3).
"""

from __future__ import annotations

import pytest

from tests.module_helpers import House, house, set_module


def _module(body: dict, key: str) -> dict:
    return next(m for m in body["modules"] if m["key"] == key)


async def _get(client, h: House, member: int) -> dict:
    resp = await client.get("/api/modules", headers=h.headers(member))
    assert resp.status_code == 200, resp.text
    return resp.json()


async def _legacy(client, h: House, member: int, enabled: bool):
    return await client.put(
        f"/api/foyers/{h.foyer_id}/gamification",
        json={"enabled": enabled},
        headers=h.headers(member),
    )


@pytest.mark.asyncio
async def test_the_legacy_foyer_switch_turns_the_motivation_module_off(client):
    async with house() as h:
        await set_module(h, "motivation", True, updated_by=None)

        resp = await _legacy(client, h, 1, False)
        assert resp.status_code == 200, resp.text
        assert resp.json()["gamification_enabled"] is False

        for member in (0, 1):
            motivation = _module(await _get(client, h, member), "motivation")
            assert motivation["enabled"] is False, f"member {member} still sees it on"

        resp = await _legacy(client, h, 0, True)
        assert resp.status_code == 200, resp.text
        assert _module(await _get(client, h, 1), "motivation")["enabled"] is True


@pytest.mark.asyncio
async def test_the_legacy_switch_keeps_the_dependency_guard(client):
    async with house() as h:
        await set_module(h, "motivation", True, updated_by=None)
        await set_module(h, "bonus_malus", True, updated_by=None)

        resp = await _legacy(client, h, 0, False)
        assert resp.status_code == 409, resp.text
        assert _module(await _get(client, h, 0), "motivation")["enabled"] is True


@pytest.mark.asyncio
async def test_another_members_change_is_named(client):
    async with house() as h:
        await set_module(h, "motivation", True, updated_by=None)
        resp = await client.put(
            "/api/modules/motivation", json={"enabled": False}, headers=h.headers(1)
        )
        assert resp.status_code == 200, resp.text

        seen_by_other = _module(await _get(client, h, 0), "motivation")
        assert seen_by_other["changed_by_name"] == "M1"
        assert seen_by_other["changed_at"] is not None

        seen_by_actor = _module(await _get(client, h, 1), "motivation")
        assert seen_by_actor["changed_by_name"] is None
        assert seen_by_actor["changed_at"] is None


@pytest.mark.asyncio
async def test_no_name_without_another_author(client):
    async with house() as h:
        # Unsigned: what the migration backfill looks like.
        await set_module(h, "presence", False, updated_by=None)
        # A personal module, written for member 0 by member 1.
        await set_module(h, "statistics", False, user=0, updated_by=1)
        body = await _get(client, h, 0)
        for key in ("presence", "statistics", "motivation"):
            assert _module(body, key)["changed_by_name"] is None, key
