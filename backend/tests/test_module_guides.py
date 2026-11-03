"""Module guide pages: registry text, "seen" state, "due" state, backfill.

Invariant: a member is shown a module's guide once, wherever they open the app;
the server says which guide is due and never one that nobody needs to read.

PATHS by which `guide_seen` / `guide_due` can be wrong:
  (1) registry text missing in one locale .............................. COVERED
  (2) GET /api/modules localisation of the guide text ................... COVERED
  (3) POST guide-seen: idempotent, unknown key, one row per user ........ COVERED
  (4) seen is per USER: same answer from another foyer of the same user . COVERED
  (5) due: a collective module another member switched on .............. COVERED
  (6) not due: the actor, a row with no author (backfill), a module that
      is off, a personal module, a guide already seen ................... COVERED
  (7) the migration backfill (the very SQL statement of the migration) .. COVERED
  (8) PUT /api/modules/{key} response carries guide_seen ................ COVERED
  (9) the DISCOVER_MODULE prompt context ........ in test_module_usage_triggers.py
Not covered: the downgrade of the migration (run by hand, `alembic downgrade -1`
then `upgrade head`, see the delivery report); the Android side.
"""

from __future__ import annotations

import importlib.util
import uuid
from pathlib import Path

import pytest
from sqlalchemy import select

from app.services.modules import get_registry
from tests.module_helpers import House, house, set_module

MIGRATION = (
    Path(__file__).parent.parent / "alembic" / "versions" / "b8c4d2e6f1a3_module_guide_views.py"
)


def _module(body: dict, key: str) -> dict:
    return next(m for m in body["modules"] if m["key"] == key)


async def _get(client, h: House, member: int, lang: str | None = None) -> dict:
    headers = h.headers(member)
    if lang:
        headers["Accept-Language"] = lang
    resp = await client.get("/api/modules", headers=headers)
    assert resp.status_code == 200, resp.text
    return resp.json()


async def _seen_rows(user_id: uuid.UUID) -> list[str]:
    from app.database import async_session
    from app.models.module_guide_view import ModuleGuideView

    async with async_session() as session:
        rows = await session.execute(
            select(ModuleGuideView.module_key).where(ModuleGuideView.user_id == user_id)
        )
        return sorted(rows.scalars().all())


# --- Registry ---


def test_every_module_has_a_guide_in_both_locales():
    for module in get_registry().modules:
        for field_name in ("guide_why", "guide_how"):
            labels = getattr(module, field_name)
            for locale in ("fr", "en"):
                assert labels.get(locale, "").strip(), (
                    f"{module.key}.{field_name} missing {locale}"
                )


# --- GET /api/modules ---


@pytest.mark.asyncio
async def test_the_guide_text_follows_the_request_locale(client):
    async with house() as h:
        fr = _module(await _get(client, h, 0, "fr"), "presence")
        en = _module(await _get(client, h, 0, "en"), "presence")
    module = get_registry().by_key("presence")
    assert fr["guide_why"] == module.guide_why["fr"]
    assert fr["guide_how"] == module.guide_how["fr"]
    assert en["guide_why"] == module.guide_why["en"]
    assert en["guide_how"] == module.guide_how["en"]
    assert fr["guide_why"] != en["guide_why"]


@pytest.mark.asyncio
async def test_marking_a_guide_seen_is_idempotent(client):
    async with house() as h:
        before = _module(await _get(client, h, 0), "presence")
        assert before["guide_seen"] is False

        for _ in range(2):
            resp = await client.post(
                "/api/modules/presence/guide-seen", headers=h.headers(0)
            )
            assert resp.status_code == 204, resp.text

        assert await _seen_rows(h.user_ids[0]) == ["presence"]
        assert _module(await _get(client, h, 0), "presence")["guide_seen"] is True
        # The other member has seen nothing.
        assert _module(await _get(client, h, 1), "presence")["guide_seen"] is False


@pytest.mark.asyncio
async def test_an_unknown_module_guide_is_a_404(client):
    async with house() as h:
        resp = await client.post("/api/modules/nope/guide-seen", headers=h.headers(0))
        assert resp.status_code == 404
        assert await _seen_rows(h.user_ids[0]) == []


@pytest.mark.asyncio
async def test_seen_belongs_to_the_user_not_to_the_foyer(client):
    from app.database import async_session
    from app.models.user_foyer import UserFoyer

    async with house() as first, house() as second:
        async with async_session() as session:
            session.add(
                UserFoyer(
                    user_id=first.user_ids[0], foyer_id=second.foyer_id, role="member"
                )
            )
            await session.commit()
        resp = await client.post(
            "/api/modules/presence/guide-seen", headers=first.headers(0)
        )
        assert resp.status_code == 204

        # Same person, other household: token for the second foyer.
        from app.auth import create_token

        token = create_token(
            str(first.user_ids[0]), str(uuid.uuid4()), foyer_id=str(second.foyer_id)
        )
        other = await client.get(
            "/api/modules", headers={"Authorization": f"Bearer {token}"}
        )
        assert other.status_code == 200, other.text
        assert _module(other.json(), "presence")["guide_seen"] is True


@pytest.mark.asyncio
async def test_the_toggle_response_carries_guide_seen(client):
    async with house() as h:
        await client.post("/api/modules/presence/guide-seen", headers=h.headers(0))
        resp = await client.put(
            "/api/modules/presence", headers=h.headers(0), json={"enabled": True}
        )
        assert resp.status_code == 200, resp.text
        assert resp.json()["guide_seen"] is True
        assert resp.json()["guide_due"] is False
        other = await client.put(
            "/api/modules/auto_adjust", headers=h.headers(0), json={"enabled": True}
        )
        assert other.json()["guide_seen"] is False


# --- guide_due ---


@pytest.mark.asyncio
async def test_a_module_enabled_by_another_member_is_due_until_seen(client):
    async with house() as h:
        await set_module(h, "presence", True, updated_by=1)

        mine = _module(await _get(client, h, 0), "presence")
        assert mine["enabled"] is True
        assert mine["guide_due"] is True, "the other member enabled it, I have not read it"
        # The member who switched it on saw the guide when doing so.
        assert _module(await _get(client, h, 1), "presence")["guide_due"] is False

        await client.post("/api/modules/presence/guide-seen", headers=h.headers(0))
        after = _module(await _get(client, h, 0), "presence")
        assert after["guide_due"] is False
        assert after["guide_seen"] is True


@pytest.mark.asyncio
async def test_a_guide_is_not_due_when_nobody_needs_to_read_it(client):
    async with house() as h:
        # No author: what the migration backfill or a legacy row looks like.
        await set_module(h, "presence", True, updated_by=None)
        # Switched OFF by the other member.
        await set_module(h, "objectives", False, updated_by=1)
        # A personal module, written for member 0 by member 1.
        await set_module(h, "statistics", True, user=0, updated_by=1)
        # Never decided: a default-on personal module with no state row.
        body = await _get(client, h, 0)
        for key in ("presence", "objectives", "statistics", "reminders", "motivation"):
            assert _module(body, key)["guide_due"] is False, key


@pytest.mark.asyncio
async def test_several_due_guides_are_listed_in_registry_order(client):
    """The client shows the first one per open; the server must not shuffle them."""
    async with house() as h:
        await set_module(h, "auto_adjust", True, updated_by=1)
        await set_module(h, "presence", True, updated_by=1)
        body = await _get(client, h, 0)
        assert [m["key"] for m in body["modules"] if m["guide_due"]] == [
            "presence",
            "auto_adjust",
        ]


# --- Migration backfill ---


def _backfill_sql() -> str:
    spec = importlib.util.spec_from_file_location("guide_views_migration", MIGRATION)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.BACKFILL_SQL


@pytest.mark.asyncio
async def test_the_backfill_marks_seen_what_each_active_member_has_on():
    from datetime import datetime, timezone

    from sqlalchemy import text, update

    from app.database import async_session
    from app.models.user_foyer import UserFoyer

    async with house(members=3) as h:
        # Member 2 has left: no rows for them, whatever the household has on.
        async with async_session() as session:
            await session.execute(
                update(UserFoyer)
                .where(
                    UserFoyer.foyer_id == h.foyer_id,
                    UserFoyer.user_id == h.user_ids[2],
                )
                .values(left_at=datetime.now(timezone.utc), role="former_member")
            )
            await session.commit()
        await set_module(h, "presence", True)  # collective, on
        await set_module(h, "objectives", False)  # collective, off
        await set_module(h, "statistics", True, user=0)  # personal, member 0 on
        await set_module(h, "statistics", False, user=1)  # personal, member 1 off
        await set_module(h, "reminders", True, user=1)  # personal, member 1 on

        async with async_session() as session:
            await session.execute(text(_backfill_sql()))
            await session.execute(text(_backfill_sql()))  # replay: no duplicate, no error
            await session.commit()

        assert await _seen_rows(h.user_ids[0]) == ["presence", "statistics"]
        assert await _seen_rows(h.user_ids[1]) == ["presence", "reminders"]
        assert await _seen_rows(h.user_ids[2]) == []
