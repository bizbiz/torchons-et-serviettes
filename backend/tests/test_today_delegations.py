"""« À faire aujourd'hui »: who may hand a task, to whom, who cancels it, what ends it.

Spec: docs/plans/a-faire-aujourdhui.md. Household of tests/today_helpers.py: Alice hands,
Bob and Carol receive.

PATHS by which a delegation is written or ended (grep of TaskDelegation writes):
  (1) /sync INSERT on task_delegations (services/delegations.create_delegation) ..... COVERED
      - to members who see the task: written, served by GET /api/delegations to all
      - to the author herself, or to a member a solo task is hidden from: `rejected`
      - a second one on a task that already has one: acknowledged, not written
      - household switch (`today` module) off: acknowledged, not written, GET empty
      - a task already completed today: acknowledged, not written
  (2) /sync DELETE (cancel_delegation): the author's ends it, another member's does not ... COVERED
  (3) completion through /sync INSERT, by a recipient or a third member .................... COVERED
      - dated before the delegation's day (UTC): does not settle it ...................... COVERED
  (4) completion through REST POST /api/tasks/{id}/complete ................................ COVERED
  (5) the /sync pull serves the full row to the other devices ............................ COVERED
  non couvert : REST collective completion (same `_after_completion` call as (4), one line).
  non couvert : the 03:30 cleanup / task deletion: an ended task's delegation stays in the table,
                every read joins on a live task (services/delegations.visible_delegations).
"""

from __future__ import annotations

import pytest
import pytest_asyncio

from tests.today_helpers import (
    ALICE, BOB, CAROL, cancel, complete, delegate, drop_household_tokens, headers, listed,
    new_task, reset_state, seed_household, sync,
)

# A day that is never the delegation's day (created by the test run, after it).
LONG_AGO = "2026-01-10T12:00:00+00:00"


@pytest_asyncio.fixture(scope="module", autouse=True)
async def household(client):
    await seed_household()
    yield
    await drop_household_tokens()


@pytest_asyncio.fixture(autouse=True)
async def clean_state():
    await reset_state()
    yield
    await reset_state()


@pytest.mark.asyncio
async def test_a_handed_task_is_seen_by_every_member_with_its_author_and_recipients(client):
    task = await new_task(client, "Poubelles", [ALICE, BOB, CAROL])
    delegation_id, out = await delegate(client, task, [BOB])
    assert not out["conflicts"] and len(out["applied"]) == 1, out
    for member in (ALICE, BOB, CAROL):
        seen = (await listed(client, member))[task]
        assert seen["id"] == delegation_id
        assert seen["author_id"] == ALICE and seen["recipient_ids"] == [BOB]


@pytest.mark.asyncio
async def test_only_the_author_cancels(client):
    task = await new_task(client, "Vaisselle", [ALICE, BOB, CAROL])
    delegation_id, _ = await delegate(client, task, [BOB, CAROL])
    for other in (BOB, CAROL):
        out = await cancel(client, delegation_id, other)
        assert not out["conflicts"], out  # acknowledged, changes nothing
        assert task in await listed(client, ALICE)
    await cancel(client, delegation_id, ALICE)
    assert task not in await listed(client, BOB)


@pytest.mark.asyncio
async def test_a_recipient_must_see_the_task_and_be_someone_else(client):
    shared = await new_task(client, "Linge", [ALICE, BOB])
    solo = await new_task(client, "Soin perso", [ALICE])
    for task, recipients in ((shared, [ALICE]), (shared, [CAROL]), (solo, [BOB]), (shared, [])):
        _, out = await delegate(client, task, recipients)
        assert [c["reason"] for c in out["conflicts"]] == ["rejected"], (recipients, out)
        assert task not in await listed(client, ALICE)


@pytest.mark.asyncio
async def test_a_second_delegation_of_the_same_task_is_not_written(client):
    task = await new_task(client, "Aspirateur", [ALICE, BOB, CAROL])
    first, _ = await delegate(client, task, [BOB])
    second, out = await delegate(client, task, [ALICE], author=CAROL)
    assert not out["conflicts"], out
    assert (await listed(client, CAROL))[task]["id"] == first != second


@pytest.mark.asyncio
@pytest.mark.parametrize("doer", [BOB, CAROL], ids=["recipient", "third_member"])
async def test_a_completion_through_sync_by_anyone_settles_it(client, doer):
    task = await new_task(client, "Courses", [ALICE, BOB, CAROL])
    await delegate(client, task, [BOB])
    await complete(client, task, doer)
    assert task not in await listed(client, ALICE)


@pytest.mark.asyncio
async def test_a_completion_dated_before_the_delegation_day_does_not_settle_it(client):
    """« Done last week », recorded now, is not what was asked for today (same rule as the app)."""
    task = await new_task(client, "Frigo", [ALICE, BOB])
    await delegate(client, task, [BOB])
    await complete(client, task, BOB, completed_at=LONG_AGO)
    assert task in await listed(client, ALICE)


@pytest.mark.asyncio
async def test_a_task_already_done_today_is_not_handed(client):
    """It would be settled at once; the app hides it by the same rule, so nothing is written."""
    task = await new_task(client, "Lave-linge", [ALICE, BOB])
    await complete(client, task, BOB)
    _, out = await delegate(client, task, [BOB])
    assert not out["conflicts"], out
    assert task not in await listed(client, ALICE)


@pytest.mark.asyncio
async def test_a_rest_completion_settles_it(client):
    task = await new_task(client, "Vitres", [ALICE, BOB])
    await delegate(client, task, [BOB])
    r = await client.post(f"/api/tasks/{task}/complete", json={}, headers=headers(BOB))
    assert r.status_code == 201, r.text
    assert task not in await listed(client, BOB)


@pytest.mark.asyncio
async def test_the_pull_carries_the_whole_row_then_its_end(client):
    task = await new_task(client, "Plantes", [ALICE, BOB, CAROL])
    since = (await sync(client, CAROL, [], since=10**12))["server_version"]
    delegation_id, _ = await delegate(client, task, [BOB])
    await complete(client, task, BOB)
    pulled = await sync(client, CAROL, [], since=since)
    rows = [c for c in pulled["server_changes"] if c["row_id"] == delegation_id]
    assert [c["operation"] for c in rows] == ["INSERT", "UPDATE"], rows
    assert rows[0]["data"]["author_id"] == ALICE and rows[0]["data"]["recipient_ids"] == [BOB]
    assert rows[1]["data"]["settled_at"]


@pytest.mark.asyncio
async def test_household_switch_off_writes_and_serves_nothing(client):
    task = await new_task(client, "Lit", [ALICE, BOB])
    await delegate(client, task, [BOB])
    await reset_state(module_on=False)
    assert await listed(client, BOB) == {}, "an existing delegation is no longer served"
    other = await new_task(client, "Salle de bain", [ALICE, BOB])
    _, out = await delegate(client, other, [BOB])
    assert not out["conflicts"], out
    assert await rows_of(other) == 0, "written while off"


async def rows_of(task_id: str) -> int:
    import uuid

    from sqlalchemy import func, select

    from app.database import async_session
    from app.models.task_delegation import TaskDelegation

    async with async_session() as session:
        return await session.scalar(
            select(func.count()).select_from(TaskDelegation).where(TaskDelegation.task_id == uuid.UUID(task_id))
        )
