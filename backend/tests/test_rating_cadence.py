"""Cadence of the automatic rating pop-up: a per-member gap in days, kept on the server.

Rule (decided 2026-11-03): the gap starts at 1; an automatic pop-up dismissed without a
rating adds 1 (cap 10); one where the member rates subtracts 1 (floor 1); after each
outcome the next automatic pop-up waits `gap` days from now.

PATHS that write `rating_prompt_gap_days` / `rating_prompt_next_at` (grep in app/):
  (1) POST /api/rating-prompt/outcome ... COVERED (floor, cap, step, next_at, isolation)
  (2) PUT /api/settings ................. COVERED (field absent from SettingsUpdate: ignored)
  (3) /sync UPDATE on user_settings ..... COVERED (generic setattr, guarded by name)
  (4) column defaults (migration + model) COVERED by test_starts_at_one_and_due
non couvert : two devices reporting at the same instant (row lock in the route), it needs
  true parallel DB sessions and the race only costs one step of the gap.
non couvert : the Android side (which gesture sends which outcome), see RatingCadenceTest.
The clock enters only as offsets from now (hours, seconds), never as a time of day.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import update

from app.services.rating_cadence import MAX_GAP_DAYS, MIN_GAP_DAYS
from tests.conftest import TEST_FOYER_ID


async def _fresh_member_headers() -> tuple[dict, uuid.UUID]:
    from app.auth import create_token
    from app.database import async_session
    from app.models.user import User
    from app.models.user_foyer import UserFoyer

    user_id = uuid.uuid4()
    async with async_session() as session:
        session.add(User(id=user_id, username=f"cad-{user_id.hex[:12]}", display_name="Cadence",
                         last_active_foyer_id=uuid.UUID(TEST_FOYER_ID)))
        await session.flush()
        session.add(UserFoyer(user_id=user_id, foyer_id=uuid.UUID(TEST_FOYER_ID), role="member"))
        await session.commit()
    token = create_token(str(user_id), str(uuid.uuid4()), TEST_FOYER_ID)
    return {"Authorization": f"Bearer {token}"}, user_id


async def _outcome(client, headers, answered: bool) -> dict:
    resp = await client.post("/api/rating-prompt/outcome", json={"answered": answered},
                             headers=headers)
    assert resp.status_code == 200, resp.text
    return resp.json()


async def _due(client, headers) -> bool:
    resp = await client.get("/api/settings", headers=headers)
    assert resp.status_code == 200, resp.text
    return resp.json()["rating_prompt_due"]


async def _set_next_at(user_id: uuid.UUID, when: datetime) -> None:
    from app.database import async_session
    from app.models.user_settings import UserSettings

    async with async_session() as session:
        await session.execute(
            update(UserSettings).where(UserSettings.user_id == user_id)
            .values(rating_prompt_next_at=when))
        await session.commit()


@pytest.mark.asyncio
async def test_constants_are_the_decided_bounds():
    assert (MIN_GAP_DAYS, MAX_GAP_DAYS) == (1, 10)


@pytest.mark.asyncio
async def test_starts_at_one_and_due(client):
    headers, _ = await _fresh_member_headers()
    assert await _due(client, headers) is True
    # the first dismissal moves 1 -> 2: proves the starting value was 1
    assert (await _outcome(client, headers, False))["gap_days"] == 2


@pytest.mark.asyncio
async def test_each_dismissal_adds_one_up_to_the_cap(client):
    headers, _ = await _fresh_member_headers()
    seen = [(await _outcome(client, headers, False))["gap_days"] for _ in range(12)]
    assert seen == [2, 3, 4, 5, 6, 7, 8, 9, 10, 10, 10, 10]


@pytest.mark.asyncio
async def test_each_answer_subtracts_one_down_to_the_floor(client):
    headers, _ = await _fresh_member_headers()
    for _ in range(4):
        await _outcome(client, headers, False)  # gap 5
    seen = [(await _outcome(client, headers, True))["gap_days"] for _ in range(6)]
    assert seen == [4, 3, 2, 1, 1, 1]


@pytest.mark.asyncio
async def test_next_at_is_gap_days_from_now(client):
    headers, _ = await _fresh_member_headers()
    before = datetime.now(timezone.utc)
    state = await _outcome(client, headers, False)  # gap 2
    after = datetime.now(timezone.utc)
    next_at = datetime.fromisoformat(state["next_at"])
    assert before + timedelta(days=2) <= next_at <= after + timedelta(days=2)


@pytest.mark.asyncio
async def test_not_due_right_after_an_outcome_and_due_once_next_at_passed(client):
    headers, user_id = await _fresh_member_headers()
    await _outcome(client, headers, True)
    assert await _due(client, headers) is False
    await _set_next_at(user_id, datetime.now(timezone.utc) + timedelta(hours=1))
    assert await _due(client, headers) is False
    await _set_next_at(user_id, datetime.now(timezone.utc) - timedelta(seconds=1))
    assert await _due(client, headers) is True


@pytest.mark.asyncio
async def test_members_do_not_share_a_gap(client):
    headers_a, _ = await _fresh_member_headers()
    headers_b, _ = await _fresh_member_headers()
    for _ in range(3):
        await _outcome(client, headers_a, False)
    assert await _due(client, headers_b) is True
    assert (await _outcome(client, headers_b, False))["gap_days"] == 2


@pytest.mark.asyncio
async def test_settings_put_and_sync_cannot_move_the_gap(client):
    headers, _ = await _fresh_member_headers()
    await client.put("/api/settings", json={"rating_prompt_gap_days": 9}, headers=headers)
    body = {"device_id": str(uuid.uuid4()), "last_server_version": 0, "changes": [{
        "client_op_id": str(uuid.uuid4()), "table": "user_settings", "operation": "UPDATE",
        "row_id": str(uuid.uuid4()),
        "data": {"rating_prompt_gap_days": 9, "rating_prompt_next_at": None},
        "client_timestamp": "2026-04-01T09:00:00+00:00"}]}
    resp = await client.post("/api/sync", json=body, headers=headers)
    assert resp.status_code == 200, resp.text
    assert (await _outcome(client, headers, False))["gap_days"] == 2
