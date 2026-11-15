"""Target share of each member (fair-share): default 1/N, strict PUT, change history.

Every test builds its OWN household (fresh ids), so nothing depends on what the shared
test household holds and the tests can run in any order.

Rules and the test that guards each:
- a member without a row has 1/N (2 and 3 members), in /fair-share/status AND in the
  leaderboard score ....................... test_default_*
- PUT needs an entry for every active member, once each ........ test_put_refused_*member*
- PUT needs a total of 1 +/- 0.01 (edge included) .............. test_put_*total*
- a refused PUT writes nothing (no row, no history) ............ test_put_refused_*
- one history line per accepted PUT, right author, right before/after, newest first,
  isolated by household, names read at display time ........... test_history_*
- a user of another household is refused (400) ................. test_foyer_isolation.py

Paths that can break the invariant "the target shown is the one in force", by grep:
- readers of `target_ratio`: /fair-share/status and /leaderboard (stats.py), both covered by
  the default tests; no other reader (grep `FairShareConfig` over app/);
- writers of `fair_share_config`: PUT /fair-share/config only (covered), and the nightly
  anonymization that deletes a departed member's row (not covered: no test reads it);
  /sync has no fair-share table, so it is not a path.
Not covered: a member who JOINS after targets were set gets the 1/N default next to rows that
already add up to 1 (total over 100 until the next PUT), by decision of the spec: the PUT
asks everybody again; two PUTs racing (last one wins, two history lines); the history `limit`
parameter; the 422 text in English (the catalogue is shared with every other error).
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone

import pytest
from sqlalchemy import select

from app.auth import create_token

DEVICE = str(uuid.uuid4())


async def _household(size: int) -> tuple[str, list[str]]:
    """A new household with `size` active members. Returns (foyer_id, [user ids])."""
    from app.database import async_session
    from app.models.foyer import Foyer
    from app.models.user import User
    from app.models.user_foyer import UserFoyer

    foyer_id = uuid.uuid4()
    user_ids = [uuid.uuid4() for _ in range(size)]
    async with async_session() as session:
        session.add(Foyer(id=foyer_id, name=f"Fair share {foyer_id.hex[:6]}"))
        await session.flush()
        for index, user_id in enumerate(user_ids):
            session.add(User(
                id=user_id, username=f"fs{user_id.hex[:12]}",
                display_name=f"Member {index} {user_id.hex[:4]}",
                last_active_foyer_id=foyer_id,
            ))
        await session.flush()
        for index, user_id in enumerate(user_ids):
            session.add(UserFoyer(
                user_id=user_id, foyer_id=foyer_id, role="admin" if index == 0 else "member",
            ))
        await session.commit()
    return str(foyer_id), [str(u) for u in user_ids]


def _h(user_id: str, foyer_id: str) -> dict:
    return {"Authorization": f"Bearer {create_token(user_id, DEVICE, foyer_id=foyer_id)}"}


def _body(percents: dict[str, int]) -> list[dict]:
    """What the app sends: whole percentages divided by 100."""
    return [{"user_id": uid, "target_ratio": pct / 100} for uid, pct in percents.items()]


async def _put(client, foyer_id, author, percents):
    return await client.put(
        "/api/fair-share/config", headers=_h(author, foyer_id), json=_body(percents)
    )


async def _targets(client, foyer_id, viewer) -> dict[str, float]:
    r = await client.get("/api/fair-share/status", headers=_h(viewer, foyer_id))
    assert r.status_code == 200, r.text
    return {row["user_id"]: row["target_ratio"] for row in r.json()}


async def _history(client, foyer_id, viewer) -> list[dict]:
    r = await client.get("/api/fair-share/history", headers=_h(viewer, foyer_id))
    assert r.status_code == 200, r.text
    return r.json()


async def _stored_rows(foyer_id: str) -> int:
    from app.database import async_session
    from app.models.fair_share import FairShareConfig

    async with async_session() as session:
        rows = await session.execute(
            select(FairShareConfig.id).where(FairShareConfig.foyer_id == uuid.UUID(foyer_id))
        )
        return len(rows.all())


# --- Default target: 1 / number of active members -----------------------------------------

async def test_default_target_is_the_equal_share_for_two_members(client):
    foyer, users = await _household(2)
    targets = await _targets(client, foyer, users[0])
    assert len(targets) == 2, targets
    assert all(t == pytest.approx(0.5) for t in targets.values()), targets


async def test_default_target_is_the_equal_share_for_three_members(client):
    foyer, users = await _household(3)
    targets = await _targets(client, foyer, users[0])
    assert len(targets) == 3, targets
    assert all(t == pytest.approx(1 / 3) for t in targets.values()), targets
    assert sum(targets.values()) == pytest.approx(1.0)


async def test_default_target_reaches_the_leaderboard_score(client):
    """The leaderboard divides points by the target: the default must be 1/N there too."""
    from app.database import async_session
    from app.models.completion import TaskCompletion
    from app.models.task import Task

    foyer, users = await _household(3)
    task_id = uuid.uuid4()
    async with async_session() as session:
        session.add(Task(id=task_id, foyer_id=uuid.UUID(foyer), title="fs leaderboard"))
        await session.flush()
        session.add(TaskCompletion(
            task_id=task_id, completed_by=uuid.UUID(users[0]), points_earned=6,
            # Fixed past date, period=all below: the result does not depend on the hour.
            completed_at=datetime(2026, 1, 15, 10, 0, tzinfo=timezone.utc),
        ))
        await session.commit()
    r = await client.get("/api/leaderboard?period=all", headers=_h(users[0], foyer))
    assert r.status_code == 200, r.text
    scores = {row["user_id"]: row["normalized_score"] for row in r.json()}
    # 6 points at a target of 1/3 -> 18.0 (at the old default of 0.5 it would be 12.0).
    assert scores[users[0]] == pytest.approx(18.0), scores


async def test_default_counts_active_members_only(client):
    from app.database import async_session
    from app.models.user_foyer import UserFoyer

    foyer, users = await _household(3)
    async with async_session() as session:
        membership = (await session.execute(select(UserFoyer).where(
            UserFoyer.user_id == uuid.UUID(users[2]), UserFoyer.foyer_id == uuid.UUID(foyer),
        ))).scalar_one()
        membership.left_at = datetime(2026, 1, 1, tzinfo=timezone.utc)
        await session.commit()
    targets = await _targets(client, foyer, users[0])
    assert set(targets) == {users[0], users[1]}, targets
    assert all(t == pytest.approx(0.5) for t in targets.values()), targets


# --- PUT: every active member, total 100 ---------------------------------------------------

async def test_put_stores_the_shares_for_three_members(client):
    foyer, users = await _household(3)
    r = await _put(client, foyer, users[0], {users[0]: 50, users[1]: 30, users[2]: 20})
    assert r.status_code == 200, r.text
    targets = await _targets(client, foyer, users[1])
    assert targets == pytest.approx({users[0]: 0.5, users[1]: 0.3, users[2]: 0.2})


async def test_put_refused_when_the_total_is_not_100(client):
    foyer, users = await _household(3)
    r = await _put(client, foyer, users[0], {users[0]: 30, users[1]: 30, users[2]: 30})
    assert r.status_code == 422, r.text
    assert r.json()["code"] == "FAIR_SHARE_TOTAL_INVALID", r.text
    assert "90" in r.json()["detail"], r.text
    assert await _stored_rows(foyer) == 0
    assert await _history(client, foyer, users[0]) == []


async def test_put_refused_when_the_total_is_over_100(client):
    foyer, users = await _household(2)
    r = await _put(client, foyer, users[0], {users[0]: 60, users[1]: 60})
    assert r.status_code == 422, r.text
    assert r.json()["code"] == "FAIR_SHARE_TOTAL_INVALID", r.text


async def test_put_total_at_the_tolerance_edge_is_accepted_and_beyond_is_refused(client):
    """1 +/- 0.01, edge included: 0.33 x 3 is 0.99 (a float sum a hair over 0.01 away)."""
    foyer, users = await _household(3)
    edge = await _put(client, foyer, users[0], {users[0]: 33, users[1]: 33, users[2]: 33})
    assert edge.status_code == 200, edge.text
    beyond = await _put(client, foyer, users[0], {users[0]: 33, users[1]: 33, users[2]: 32})
    assert beyond.status_code == 422, beyond.text


async def test_put_refused_when_a_member_is_missing(client):
    foyer, users = await _household(3)
    r = await _put(client, foyer, users[0], {users[0]: 50, users[1]: 50})
    assert r.status_code == 422, r.text
    assert r.json()["code"] == "FAIR_SHARE_MEMBERS_MISMATCH", r.text
    assert await _stored_rows(foyer) == 0
    assert await _history(client, foyer, users[0]) == []


async def test_put_refused_when_a_member_is_sent_twice(client):
    foyer, users = await _household(2)
    body = [
        {"user_id": users[0], "target_ratio": 0.5},
        {"user_id": users[0], "target_ratio": 0.5},
    ]
    r = await client.put("/api/fair-share/config", headers=_h(users[0], foyer), json=body)
    assert r.status_code == 422, r.text
    assert r.json()["code"] == "FAIR_SHARE_MEMBERS_MISMATCH", r.text


async def test_put_does_not_ask_for_a_member_who_left_and_refuses_them(client):
    from app.database import async_session
    from app.models.user_foyer import UserFoyer

    foyer, users = await _household(3)
    async with async_session() as session:
        membership = (await session.execute(select(UserFoyer).where(
            UserFoyer.user_id == uuid.UUID(users[2]), UserFoyer.foyer_id == uuid.UUID(foyer),
        ))).scalar_one()
        membership.left_at = datetime(2026, 1, 1, tzinfo=timezone.utc)
        await session.commit()
    ok = await _put(client, foyer, users[0], {users[0]: 70, users[1]: 30})
    assert ok.status_code == 200, ok.text
    with_leaver = await _put(client, foyer, users[0], {users[0]: 50, users[1]: 30, users[2]: 20})
    assert with_leaver.status_code == 400, with_leaver.text


# --- History --------------------------------------------------------------------------------

async def test_history_has_one_line_per_accepted_put_newest_first(client):
    foyer, users = await _household(2)
    assert await _history(client, foyer, users[0]) == []
    first = await _put(client, foyer, users[0], {users[0]: 60, users[1]: 40})
    refused = await _put(client, foyer, users[0], {users[0]: 10, users[1]: 10})
    second = await _put(client, foyer, users[1], {users[0]: 50, users[1]: 50})
    assert (first.status_code, refused.status_code, second.status_code) == (200, 422, 200)
    history = await _history(client, foyer, users[0])
    assert len(history) == 2, history
    assert history[0]["changed_by"] == users[1], history
    assert history[1]["changed_by"] == users[0], history
    assert history[0]["changed_at"] >= history[1]["changed_at"], history


async def test_history_gives_the_author_and_the_shares_before_and_after(client):
    foyer, users = await _household(2)
    # The author is the SECOND user: a history that took the first entry of the body for
    # the author would name the wrong person.
    await _put(client, foyer, users[1], {users[0]: 60, users[1]: 40})
    await _put(client, foyer, users[0], {users[0]: 45, users[1]: 55})
    history = await _history(client, foyer, users[0])
    newest, oldest = history[0], history[1]

    def assert_shares(entry, expected):
        # pytest >= 9.2 refuses approx() on a dict of tuples: compare key by key.
        got = {s["user_id"]: (s["before"], s["after"]) for s in entry["shares"]}
        assert got.keys() == expected.keys(), entry
        for user_id, pair in expected.items():
            assert got[user_id] == pytest.approx(pair), entry

    assert oldest["changed_by"] == users[1], oldest
    # Before the first change nobody had a row: the shares in force were the 1/N default.
    assert_shares(oldest, {users[0]: (0.5, 0.6), users[1]: (0.5, 0.4)})
    assert newest["changed_by"] == users[0], newest
    assert_shares(newest, {users[0]: (0.6, 0.45), users[1]: (0.4, 0.55)})
    assert oldest["changed_by_name"].startswith("Member 1"), oldest


async def test_history_is_isolated_by_household(client):
    foyer_a, users_a = await _household(2)
    foyer_b, users_b = await _household(2)
    await _put(client, foyer_a, users_a[0], {users_a[0]: 70, users_a[1]: 30})
    assert len(await _history(client, foyer_a, users_a[0])) == 1
    assert await _history(client, foyer_b, users_b[0]) == []
    # B's own change does not appear in A either, and A's targets are untouched by it.
    await _put(client, foyer_b, users_b[0], {users_b[0]: 20, users_b[1]: 80})
    history_a = await _history(client, foyer_a, users_a[0])
    assert len(history_a) == 1, history_a
    assert {s["user_id"] for s in history_a[0]["shares"]} == set(users_a), history_a
    assert (await _targets(client, foyer_a, users_a[0]))[users_a[0]] == pytest.approx(0.7)


async def test_history_names_a_member_who_left_and_reads_the_anonymous_name_later(client):
    from app.database import async_session
    from app.models.user import User
    from app.models.user_foyer import UserFoyer
    from app.services.member_departure import ANONYMOUS_NAME

    foyer, users = await _household(2)
    await _put(client, foyer, users[0], {users[0]: 60, users[1]: 40})
    async with async_session() as session:
        membership = (await session.execute(select(UserFoyer).where(
            UserFoyer.user_id == uuid.UUID(users[1]), UserFoyer.foyer_id == uuid.UUID(foyer),
        ))).scalar_one()
        membership.left_at = datetime(2026, 1, 1, tzinfo=timezone.utc)
        await session.commit()

    def names(history):
        return {s["user_id"]: s["display_name"] for s in history[0]["shares"]}

    left_but_named = names(await _history(client, foyer, users[0]))
    assert left_but_named[users[1]].startswith("Member 1"), left_but_named

    # The nightly job renames an account for good once its return window is over: the
    # history keeps the line, with the name the account has now.
    async with async_session() as session:
        user = await session.get(User, uuid.UUID(users[1]))
        user.display_name = ANONYMOUS_NAME
        await session.commit()
    anonymized = names(await _history(client, foyer, users[0]))
    assert anonymized[users[1]] == ANONYMOUS_NAME, anonymized
