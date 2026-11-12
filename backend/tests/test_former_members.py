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
Not covered by the guard: a membership read WITHOUT the UserFoyer model — raw SQL on
user_foyers, a relationship such as `Foyer.members`, a view. None existed on 2026-11-12
(not proved by an exhaustive sweep); a new one must filter left_at like the others.
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


# Every way a query READS user_foyers: a compared column (any column, not only foyer_id), an
# `in_`, a join, a select of the model. Until 2026-11-12 only `UserFoyer.foyer_id ==` was seen:
# a join or a filter on `UserFoyer.user_id` forgetting left_at passed in silence (there were
# 122 reading lines then, all filtering or saying why; 22 of them seen by the old pattern).
_READS = re.compile(r"UserFoyer\.\w+\s*(==|!=|\.in_\()|join\(\s*UserFoyer\b|select\(\s*UserFoyer\b")


def _statement(lines: list[str], i: int) -> str:
    """The whole query around line i: back to its `execute(` or else its `select(` (40 lines at
    most), forward while a parenthesis is open or the next line chains a `.where(…)` / `.join(…)`.
    A ±6-line window missed a left_at filter 25 lines below its join (stats.py, weekly summary);
    stopping at the first balanced line missed `select(Foyer, UserFoyer.role)` + `.where(…)`."""
    back = range(i, max(-1, i - 41), -1)
    start = next((j for j in back if "execute(" in lines[j]), None)
    if start is None:
        start = next((j for j in back if "select(" in lines[j]), max(0, i - 6))
    depth, end = 0, start
    for end in range(start, min(len(lines), start + 80)):
        depth += lines[end].count("(") - lines[end].count(")")
        chained = end + 1 < len(lines) and lines[end + 1].lstrip().startswith(".")
        if end >= i and depth <= 0 and not chained:
            break
    return "\n".join(lines[start:end + 1])


def test_every_membership_query_says_something_about_left_at():
    root = pathlib.Path(__file__).resolve().parents[1] / "app"
    silent, seen = [], 0
    files = sorted(p for p in root.rglob("*.py") if "models" not in p.parts)
    for path in files:
        lines = path.read_text(encoding="utf-8").split("\n")
        for i, line in enumerate(lines):
            if line.lstrip().startswith("#") or not _READS.search(line):
                continue
            seen += 1
            if "left_at" not in _statement(lines, i):
                silent.append(f"{path.relative_to(root)}:{i + 1}")
    assert len(files) > 50, files  # the scan really read the app
    assert seen > 100, seen  # and really matched the reads (122 on 2026-11-12)
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
