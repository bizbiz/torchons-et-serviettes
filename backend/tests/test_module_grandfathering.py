"""A personal module without a row falls back to ON only in a foyer from before the switchover.

The backfill migration (a1b2c3d4e5f7) wrote UNSIGNED collective rows
(updated_by_user_id NULL) for every foyer that existed then; both writers since
(PUT /modules, accepting a module proposal) sign theirs. Until 2026-11-04 any
collective row counted as grandfathering, so a NEW foyer switching on its first
collective module also switched on every member's personal modules — seen on the
beta: Statistics appeared as soon as Auto-adjust was switched on.

Paths: `resolve_states` (GET /modules) and `is_module_enabled` (generators and
the prompt filter), both covered below on the same personal module.
Not covered: the two writers (`api/modules.py` PUT, `api/prompts.py` accepting a
DISCOVER_MODULE), here simulated by a signed row; that they SIGN is checked by
reading them (both set `updated_by_user_id=user_id`), not by a test.
Not covered: a row whose author was deleted (ondelete SET NULL) reads as backfill.
"""

from __future__ import annotations

import pytest

from tests.module_helpers import house, set_module

PERSONAL = "statistics"  # personal, default_new: false


async def _personal_state(client, h, member: int = 1) -> bool:
    resp = await client.get("/api/modules", headers=h.headers(member))
    assert resp.status_code == 200, resp.text
    return {m["key"]: m["enabled"] for m in resp.json()["modules"]}[PERSONAL]


async def _is_enabled(h, member: int = 1) -> bool:
    from app.database import async_session
    from app.services.modules import is_module_enabled

    async with async_session() as session:
        return await is_module_enabled(session, h.foyer_id, PERSONAL, h.user_ids[member])


@pytest.mark.asyncio
async def test_new_foyer_switching_a_collective_module_keeps_personal_defaults(client):
    async with house() as h:
        # A member switches Auto-adjust on: a SIGNED collective row.
        await set_module(h, "auto_adjust", True, updated_by=0)
        assert await _personal_state(client, h) is False
        assert await _is_enabled(h) is False


@pytest.mark.asyncio
async def test_foyer_from_the_backfill_keeps_personal_modules_on(client):
    """Witness: the grandfathering itself still works (unsigned row, as the migration wrote)."""
    async with house() as h:
        await set_module(h, "auto_adjust", False)
        assert await _personal_state(client, h) is True
        assert await _is_enabled(h) is True
