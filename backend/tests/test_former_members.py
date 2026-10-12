"""Former members (`user_foyers.left_at` set) are out of every list about the PRESENT.

Decided on 2026-10-12 (todo « Un membre parti… », triage of the 24 membership queries): pickers,
rankings, statistics, objectives, propositions, the household list, list only ACTIVE members;
the activity calendar, the restore preview and the history keep former members, on purpose.

Paths, and what covers each:
- a NEW membership query that says nothing about left_at — the guard test, over all of app/;
- `/auth/users` (pickers), `/leaderboard` (rankings), `GET /foyers` (household switcher) — one
  behaviour test each, played as a before/after on the same user.
Not covered by behaviour: the 13 other filtered queries (stats screens, fair-share, pace,
propositions, revote target, collective participants, member count, admin check), which got the
same one-line filter and are held by the guard.
"""

from __future__ import annotations

import pathlib
import re
import uuid
from datetime import datetime, timezone

import pytest
import pytest_asyncio
from sqlalchemy import select

from app.auth import create_token

STAY_ID = "f0a00000-0000-4000-8000-0000000000b1"
GONE_ID = "f0a00000-0000-4000-8000-0000000000b2"
HOUSE_ID = "f0a00000-0000-4000-8000-00000000f0b1"
OTHER_ID = "f0a00000-0000-4000-8000-00000000f0b2"
DEVICE = str(uuid.uuid4())


async def _member(user_id: str, foyer_id: str, *, left: bool = False):
    from app.database import async_session
    from app.models.foyer import Foyer
    from app.models.user import User
    from app.models.user_foyer import UserFoyer

    async with async_session() as session:
        if await session.get(Foyer, uuid.UUID(foyer_id)) is None:
            session.add(Foyer(id=uuid.UUID(foyer_id), name=f"House {foyer_id[-3:]}"))
            await session.flush()
        if await session.get(User, uuid.UUID(user_id)) is None:
            session.add(User(id=uuid.UUID(user_id), username=f"fm{user_id[-3:]}",
                             display_name=f"Name{user_id[-3:]}", last_active_foyer_id=uuid.UUID(foyer_id)))
            await session.flush()
        found = await session.execute(select(UserFoyer).where(
            UserFoyer.user_id == uuid.UUID(user_id), UserFoyer.foyer_id == uuid.UUID(foyer_id),
        ))
        uf = found.scalar_one_or_none()
        if uf is None:
            uf = UserFoyer(user_id=uuid.UUID(user_id), foyer_id=uuid.UUID(foyer_id), role="member")
            session.add(uf)
        # Any past instant: only the presence of left_at matters.
        uf.left_at = datetime(2026, 1, 1, tzinfo=timezone.utc) if left else None
        await session.commit()


def _h(user_id, foyer_id):
    return {"Authorization": f"Bearer {create_token(user_id, DEVICE, foyer_id=foyer_id)}"}


@pytest_asyncio.fixture(autouse=True)
async def household():
    await _member(STAY_ID, HOUSE_ID)
    await _member(GONE_ID, HOUSE_ID)
    await _member(GONE_ID, OTHER_ID)
    yield


def test_every_membership_query_says_something_about_left_at():
    root = pathlib.Path(__file__).resolve().parents[1] / "app"
    silent = []
    files = sorted(root.rglob("*.py"))
    for path in files:
        lines = path.read_text(encoding="utf-8").split("\n")
        for i, line in enumerate(lines):
            if re.search(r"UserFoyer\.foyer_id\s*==", line):
                window = "\n".join(lines[max(0, i - 6):i + 5])
                if "left_at" not in window:
                    silent.append(f"{path.relative_to(root)}:{i + 1}")
    assert len(files) > 50, files  # the scan really read the app
    assert silent == [], (
        "membership queries that neither filter left_at nor say why they keep former members: "
        f"{silent}"
    )


@pytest.mark.asyncio
async def test_pickers_drop_a_former_member(client):
    before = await client.get("/api/auth/users", headers=_h(STAY_ID, HOUSE_ID))
    assert GONE_ID in before.text, before.text
    await _member(GONE_ID, HOUSE_ID, left=True)
    after = await client.get("/api/auth/users", headers=_h(STAY_ID, HOUSE_ID))
    assert after.status_code == 200, after.text
    assert GONE_ID not in after.text, after.text


@pytest.mark.asyncio
async def test_leaderboard_drops_a_former_member(client):
    before = await client.get("/api/leaderboard", headers=_h(STAY_ID, HOUSE_ID))
    assert before.status_code == 200, before.text
    await _member(GONE_ID, HOUSE_ID, left=True)
    after = await client.get("/api/leaderboard", headers=_h(STAY_ID, HOUSE_ID))
    assert after.status_code == 200, after.text
    assert f"Name{GONE_ID[-3:]}" not in after.text, after.text


@pytest.mark.asyncio
async def test_household_switcher_drops_a_household_left(client):
    before = await client.get("/api/foyers", headers=_h(GONE_ID, OTHER_ID))
    assert HOUSE_ID in before.text, before.text
    await _member(GONE_ID, HOUSE_ID, left=True)
    after = await client.get("/api/foyers", headers=_h(GONE_ID, OTHER_ID))
    assert after.status_code == 200, after.text
    assert HOUSE_ID not in after.text and OTHER_ID in after.text, after.text
