"""A "set my value" request sent twice at the same moment must succeed twice, and write once.

The app's sync worker can resend a request that is still in flight. On the beta
(2026-11-20) `PUT /api/tasks/{id}/effort` answered 500 on `task_efforts_pkey` when a queued
vote was replayed that way: both copies selected no row, both inserted. The data ended
correct, the client got a 500 (and would retry, or show an error).

PATHS of the same shape (grep `scalar_one_or_none()` then `session.add(` in app/api), each a
way to lose the race on a (key, user) that a client can replay:
  (1) PUT /tasks/{id}/effort ............................ COVERED (race + replay history)
  (2) PUT /tasks/{id}/affinity .......................... COVERED (race + replay history)
  (3) PUT /tasks/{id}/frequency (service helper) ........ COVERED (race)
  (4) POST /prompts/{id}/resolve, BUMP_EFFORT ........... COVERED (no vote yet / vote exists:
      two resolves of ONE prompt used to bump twice)
  (5) PUT /modules/{key} ................................. COVERED
  (6) PUT /presence (calendar days) ...................... COVERED
  (7) POST /notifications/register ....................... COVERED
  (8) POST /objective-propositions/{id}/respond .......... COVERED (the 2nd answer is the
      intended 409, not a 500)
  (9) POST /contexts (name unique per foyer) ............. COVERED (201 then the intended 409)
  not covered: POST /presence/checkin and prompts._upsert_presence_day take the same foyer
  lock as (6) (one lock key, utils/row_lock.presence_key); the module proposal accept path
  takes the same lock as (5) (module_state_key).

Each race round uses a FRESH row (task / day / token), since the race exists only while the
row is missing. A round is repeated: a single gather can interleave without colliding.
"""

from __future__ import annotations

import asyncio
import uuid
from datetime import date, datetime, timedelta, timezone

import pytest
from sqlalchemy import delete, func, select

from tests.module_helpers import house

ROUNDS = 5


async def _new_task(h, rule: dict | None = None) -> uuid.UUID:
    """A task of the house, assigned to member 0 (the one who votes)."""
    from app.database import async_session
    from app.models.task import Task
    from app.models.task_assignment import TaskAssignment

    task_id = uuid.uuid4()
    async with async_session() as session:
        session.add(Task(
            id=task_id, foyer_id=h.foyer_id, title="race task",
            recurrence_rule=rule or {"type": "interval", "every_n_days": 7},
        ))
        await session.flush()
        session.add(TaskAssignment(task_id=task_id, user_id=h.user_ids[0]))
        await session.commit()
    return task_id


async def _scalar(stmt):
    from app.database import async_session

    async with async_session() as session:
        return (await session.execute(stmt)).scalar()


async def _vote_log_count(table: str, task_id: uuid.UUID) -> int:
    from app.models.sync import ChangeLog

    return await _scalar(
        select(func.count()).select_from(ChangeLog).where(
            ChangeLog.table_name == table, ChangeLog.row_id == task_id,
            ChangeLog.operation == "VOTE",
        )
    )


def _both_ok(a, b):
    assert (a.status_code, b.status_code) == (200, 200), (a.text[:300], b.text[:300])


# --- (1) effort -------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_two_efforts_together_both_succeed_and_write_one_row(client):
    from app.models.task import Task
    from app.models.task_effort import TaskEffort

    async with house() as h:
        for _ in range(ROUNDS):
            task_id = await _new_task(h)
            url = f"/api/tasks/{task_id}/effort"
            a, b = await asyncio.gather(
                client.put(url, headers=h.headers(0), json={"points": 3}),
                client.put(url, headers=h.headers(0), json={"points": 7}),
            )
            _both_ok(a, b)
            rows = await _scalar(select(func.count()).select_from(TaskEffort).where(
                TaskEffort.task_id == task_id))
            assert rows == 1
            points = await _scalar(select(TaskEffort.points).where(TaskEffort.task_id == task_id))
            assert points in (3, 7)
            # The consensus follows the single surviving vote.
            assert await _scalar(select(Task.weight).where(Task.id == task_id)) == points


@pytest.mark.asyncio
async def test_replayed_effort_logs_one_vote_line_and_still_touches_updated_at(client):
    from app.models.task_effort import TaskEffort

    async with house() as h:
        task_id = await _new_task(h)
        url = f"/api/tasks/{task_id}/effort"
        first = await client.put(url, headers=h.headers(0), json={"points": 5})
        stamp = await _scalar(select(TaskEffort.updated_at).where(TaskEffort.task_id == task_id))
        await asyncio.sleep(0.05)
        second = await client.put(url, headers=h.headers(0), json={"points": 5})
        _both_ok(first, second)
        assert await _vote_log_count("task_efforts", task_id) == 1
        # "Reviewed again" still counts for the rating cadence (oldest-rating, expiry).
        assert await _scalar(
            select(TaskEffort.updated_at).where(TaskEffort.task_id == task_id)) > stamp

        # A real change is a new history line, with the consensus moving.
        third = await client.put(url, headers=h.headers(0), json={"points": 9})
        assert third.status_code == 200
        assert await _vote_log_count("task_efforts", task_id) == 2


@pytest.mark.asyncio
async def test_two_identical_efforts_together_log_one_vote_line(client):
    async with house() as h:
        for _ in range(ROUNDS):
            task_id = await _new_task(h)
            url = f"/api/tasks/{task_id}/effort"
            a, b = await asyncio.gather(
                client.put(url, headers=h.headers(0), json={"points": 4}),
                client.put(url, headers=h.headers(0), json={"points": 4}),
            )
            _both_ok(a, b)
            assert await _vote_log_count("task_efforts", task_id) == 1


# --- (2) affinity -----------------------------------------------------------------------


@pytest.mark.asyncio
async def test_two_affinities_together_both_succeed_and_write_one_row(client):
    from app.models.task_affinity import TaskAffinity

    async with house() as h:
        for _ in range(ROUNDS):
            task_id = await _new_task(h)
            url = f"/api/tasks/{task_id}/affinity"
            a, b = await asyncio.gather(
                client.put(url, headers=h.headers(0), json={"score": 2}),
                client.put(url, headers=h.headers(0), json={"score": 8}),
            )
            _both_ok(a, b)
            rows = await _scalar(select(func.count()).select_from(TaskAffinity).where(
                TaskAffinity.task_id == task_id))
            assert rows == 1
            assert await _scalar(
                select(TaskAffinity.score).where(TaskAffinity.task_id == task_id)) in (2, 8)


@pytest.mark.asyncio
async def test_replayed_affinity_logs_one_vote_line(client):
    async with house() as h:
        task_id = await _new_task(h)
        url = f"/api/tasks/{task_id}/affinity"
        for _ in range(2):
            r = await client.put(url, headers=h.headers(0), json={"score": 6})
            assert r.status_code == 200, r.text
        assert await _vote_log_count("task_affinities", task_id) == 1
        r = await client.put(url, headers=h.headers(0), json={"score": 7})
        assert r.status_code == 200
        assert await _vote_log_count("task_affinities", task_id) == 2


# --- (3) frequency ----------------------------------------------------------------------


@pytest.mark.asyncio
async def test_two_frequency_votes_together_both_succeed_and_write_one_row(client):
    from app.models.task import Task
    from app.models.task_frequency_vote import TaskFrequencyVote

    async with house() as h:
        for _ in range(ROUNDS):
            task_id = await _new_task(h)
            url = f"/api/tasks/{task_id}/frequency"
            a, b = await asyncio.gather(
                client.put(url, headers=h.headers(0), json={"every_n_days": 10}),
                client.put(url, headers=h.headers(0), json={"every_n_days": 20}),
            )
            _both_ok(a, b)
            rows = await _scalar(select(func.count()).select_from(TaskFrequencyVote).where(
                TaskFrequencyVote.task_id == task_id))
            assert rows == 1
            rule = await _scalar(select(Task.recurrence_rule).where(Task.id == task_id))
            assert rule["every_n_days"] in (10, 20)


# --- (4) prompts: BUMP_EFFORT ------------------------------------------------------------


async def _bump_prompt(h, task_id) -> uuid.UUID:
    from app.database import async_session
    from app.models.enums import PromptType
    from app.models.user_prompt import UserPrompt

    prompt_id = uuid.uuid4()
    async with async_session() as session:
        session.add(UserPrompt(
            id=prompt_id, user_id=h.user_ids[0], foyer_id=h.foyer_id,
            type=PromptType.BUMP_EFFORT, context={"task_id": str(task_id)},
        ))
        await session.commit()
    return prompt_id


@pytest.mark.asyncio
async def test_two_bump_answers_together_bump_once_without_a_vote(client):
    from app.models.task_effort import TaskEffort

    async with house() as h:
        for _ in range(ROUNDS):
            task_id = await _new_task(h)
            prompt_id = await _bump_prompt(h, task_id)
            body = {"resolution": "ANSWERED", "payload": {"action": "bump_effort"}}
            a, b = await asyncio.gather(
                client.post(f"/api/prompts/{prompt_id}/resolve", headers=h.headers(0), json=body),
                client.post(f"/api/prompts/{prompt_id}/resolve", headers=h.headers(0), json=body),
            )
            _both_ok(a, b)
            assert await _scalar(select(func.count()).select_from(TaskEffort).where(
                TaskEffort.task_id == task_id)) == 1
            # No vote yet: the bump creates 4 (default 3 + 1), once.
            assert await _scalar(
                select(TaskEffort.points).where(TaskEffort.task_id == task_id)) == 4


@pytest.mark.asyncio
async def test_two_bump_answers_together_bump_an_existing_vote_once(client):
    from app.models.task_effort import TaskEffort

    async with house() as h:
        for _ in range(ROUNDS):
            task_id = await _new_task(h)
            r = await client.put(f"/api/tasks/{task_id}/effort", headers=h.headers(0),
                                 json={"points": 5})
            assert r.status_code == 200, r.text
            prompt_id = await _bump_prompt(h, task_id)
            body = {"resolution": "ANSWERED", "payload": {"action": "bump_effort"}}
            a, b = await asyncio.gather(
                client.post(f"/api/prompts/{prompt_id}/resolve", headers=h.headers(0), json=body),
                client.post(f"/api/prompts/{prompt_id}/resolve", headers=h.headers(0), json=body),
            )
            _both_ok(a, b)
            assert await _scalar(
                select(TaskEffort.points).where(TaskEffort.task_id == task_id)) == 6
            assert sorted([a.json().get("already_resolved", False),
                           b.json().get("already_resolved", False)]) == [False, True]


# --- (5) modules ------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_two_module_toggles_together_both_succeed_and_write_one_row(client):
    from app.models.module_state import ModuleState

    for _ in range(ROUNDS):
        async with house() as h:
            a, b = await asyncio.gather(
                client.put("/api/modules/motivation", headers=h.headers(0), json={"enabled": True}),
                client.put("/api/modules/motivation", headers=h.headers(1), json={"enabled": True}),
            )
            _both_ok(a, b)
            assert await _scalar(select(func.count()).select_from(ModuleState).where(
                ModuleState.foyer_id == h.foyer_id, ModuleState.module_key == "motivation",
            )) == 1


# --- (6) presence days ------------------------------------------------------------------


@pytest.mark.asyncio
async def test_two_presence_edits_together_both_succeed_and_write_one_row(client):
    from app.models.presence_day import PresenceDay

    async with house() as h:
        for i in range(ROUNDS):
            # A fixed future date: the race does not depend on what day the suite runs.
            day = date(2031, 3, 1) + timedelta(days=i)
            a, b = await asyncio.gather(
                client.put("/api/presence", headers=h.headers(0),
                           json=[{"date": day.isoformat(), "status": "present"}]),
                client.put("/api/presence", headers=h.headers(1),
                           json=[{"date": day.isoformat(), "status": "absent"}]),
            )
            _both_ok(a, b)
            assert await _scalar(select(func.count()).select_from(PresenceDay).where(
                PresenceDay.foyer_id == h.foyer_id, PresenceDay.date == day)) == 1


# --- (7) notification token -------------------------------------------------------------


@pytest.mark.asyncio
async def test_two_token_registrations_together_both_succeed_and_write_one_row(client):
    from app.database import async_session
    from app.models.notification import NotificationToken

    async with house() as h:
        try:
            for _ in range(ROUNDS):
                token = f"race-{uuid.uuid4()}"
                a, b = await asyncio.gather(
                    client.post("/api/notifications/register", headers=h.headers(0),
                                json={"fcm_token": token, "device_name": "A"}),
                    client.post("/api/notifications/register", headers=h.headers(0),
                                json={"fcm_token": token, "device_name": "B"}),
                )
                _both_ok(a, b)
                assert await _scalar(select(func.count()).select_from(NotificationToken).where(
                    NotificationToken.fcm_token == token)) == 1
        finally:
            # house() deletes the users on exit; their tokens have no ON DELETE CASCADE.
            async with async_session() as session:
                await session.execute(delete(NotificationToken).where(
                    NotificationToken.user_id.in_(h.user_ids)))
                await session.commit()


# --- (9) context name --------------------------------------------------------------------


@pytest.mark.asyncio
async def test_two_creates_of_one_context_together_give_201_then_409(client):
    from app.models.context import Context

    async with house() as h:
        for i in range(ROUNDS):
            name = f"race-room-{i}"
            a, b = await asyncio.gather(
                client.post("/api/contexts", headers=h.headers(0), json={"name": name}),
                client.post("/api/contexts", headers=h.headers(0), json={"name": name}),
            )
            assert sorted((a.status_code, b.status_code)) == [201, 409], (a.text[:300], b.text[:300])
            assert await _scalar(select(func.count()).select_from(Context).where(
                Context.foyer_id == h.foyer_id, Context.name == name)) == 1


# --- (8) proposition answer -------------------------------------------------------------


@pytest.mark.asyncio
async def test_two_answers_to_one_proposition_together_give_200_then_409(client):
    from app.database import async_session
    from app.models.objective_proposition import (
        ObjectiveProposition, ObjectivePropositionResponse)

    async with house(members=3) as h:
        for _ in range(ROUNDS):
            prop_id = uuid.uuid4()
            async with async_session() as session:
                session.add(ObjectiveProposition(
                    id=prop_id, foyer_id=h.foyer_id, proposer_id=h.user_ids[0],
                    slider_values_json={str(u): 30 for u in h.user_ids},
                    expires_at=datetime(2099, 1, 1, tzinfo=timezone.utc),
                ))
                await session.commit()
            url = f"/api/objective-propositions/{prop_id}/respond"
            a, b = await asyncio.gather(
                client.post(url, headers=h.headers(1), json={"accepted": True}),
                client.post(url, headers=h.headers(1), json={"accepted": True}),
            )
            assert sorted((a.status_code, b.status_code)) == [200, 409], (a.text[:300], b.text[:300])
            assert await _scalar(select(func.count()).select_from(ObjectivePropositionResponse)
                                 .where(ObjectivePropositionResponse.proposition_id == prop_id)) == 1
