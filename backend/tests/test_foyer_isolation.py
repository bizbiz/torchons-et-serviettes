"""Isolation between households: a member of one household must not read or write another's.

Each test plays one attack from todo.md « /sync et trois routes écrivent ou lisent hors du foyer
du jeton » with an OUTSIDER (own user, own household) against the test household.

Paths through which a household boundary can be crossed, and what covers each:
- /sync UPDATE tasks, arbitrary columns (foyer_id, version) and bounds (weight) — two tests;
- /sync INSERT tasks and REST task create/update, assignees outside the household — sync tested;
- /sync INSERT task_completions on a foreign task, and with a foreign completed_by — two tests;
- /sync DELETE task_completions on a foreign completion — one test;
- PUT /fair-share/config on a foreign user — one test;
- GET /tasks/{id}/ratings and /rating-context on a foreign task — one test;
- GET /history resolving a foreign task title from a journalled foreign id — one test.
Not covered here: REST create/update assignees, REST completed_by (POST /complete, PUT
/completions) and the /sync completion UPDATE, which share `assert_active_members` with the
covered paths; /stats/weekly-summary counting a member's completions in their OTHER households,
which needs a user in two households — fixed by reading, not tested; PUT /bonus-config by a
non-admin member, which is a role question, not an isolation one.

`client_timestamp` is `now`: it only feeds the stale-update check, which no test here exercises.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone

import pytest
import pytest_asyncio
from sqlalchemy import select

from app.auth import create_token
from tests.conftest import DEVICE_A, TEST_FOYER_ID, TEST_USER_ID

OUTSIDER_ID = "7e57a11e-0000-4000-8000-00000000beef"
OUTSIDER_FOYER_ID = "7e57a11e-0000-4000-8000-00000000f0e0"
OUTSIDER_DEVICE = str(uuid.uuid4())


def _op(table, operation, row_id=None, data=None):
    return {
        "client_op_id": str(uuid.uuid4()),
        "table": table,
        "operation": operation,
        "row_id": row_id or str(uuid.uuid4()),
        "data": data,
        "client_timestamp": datetime.now(timezone.utc).isoformat(),
    }


def _body(device, ops):
    return {"device_id": device, "last_server_version": 0, "changes": ops}


@pytest_asyncio.fixture(scope="module", autouse=True)
async def seed_outsider():
    """An outsider user who is the only member of their own household."""
    from app.database import async_session
    from app.models.foyer import Foyer
    from app.models.user import User
    from app.models.user_foyer import UserFoyer

    async with async_session() as session:
        if await session.get(Foyer, uuid.UUID(OUTSIDER_FOYER_ID)) is None:
            session.add(Foyer(id=uuid.UUID(OUTSIDER_FOYER_ID), name="Outsider household"))
            await session.flush()
        if await session.get(User, uuid.UUID(OUTSIDER_ID)) is None:
            session.add(User(
                id=uuid.UUID(OUTSIDER_ID), username="outsider", display_name="Outsider",
                last_active_foyer_id=uuid.UUID(OUTSIDER_FOYER_ID),
            ))
            await session.flush()
        found = await session.execute(select(UserFoyer).where(
            UserFoyer.user_id == uuid.UUID(OUTSIDER_ID),
            UserFoyer.foyer_id == uuid.UUID(OUTSIDER_FOYER_ID),
        ))
        if found.scalar_one_or_none() is None:
            session.add(UserFoyer(
                user_id=uuid.UUID(OUTSIDER_ID), foyer_id=uuid.UUID(OUTSIDER_FOYER_ID), role="admin",
            ))
        await session.commit()
    yield


@pytest.fixture(scope="module")
def victim_headers():
    return {"Authorization": f"Bearer {create_token(TEST_USER_ID, DEVICE_A, foyer_id=TEST_FOYER_ID)}"}


@pytest.fixture(scope="module")
def outsider_headers():
    token = create_token(OUTSIDER_ID, OUTSIDER_DEVICE, foyer_id=OUTSIDER_FOYER_ID)
    return {"Authorization": f"Bearer {token}"}


async def _sync(client, headers, device, ops):
    r = await client.post("/api/sync", json=_body(device, ops), headers=headers)
    assert r.status_code == 200, r.text
    return r.json()


async def _victim_task(client, victim_headers, title):
    task_id = str(uuid.uuid4())
    body = await _sync(client, victim_headers, DEVICE_A, [_op("tasks", "INSERT", task_id, {
        "title": title, "assigned_to_ids": [TEST_USER_ID],
    })])
    assert len(body["applied"]) == 1, body
    return task_id


async def _victim_completion(client, victim_headers, task_id):
    comp_id = str(uuid.uuid4())
    body = await _sync(client, victim_headers, DEVICE_A, [_op("task_completions", "INSERT", comp_id, {
        "task_id": task_id, "completed_by": TEST_USER_ID,
    })])
    assert len(body["applied"]) == 1, body
    return comp_id


async def _row(model, row_id):
    from app.database import async_session
    async with async_session() as session:
        return await session.get(model, uuid.UUID(row_id))


@pytest.mark.asyncio
async def test_sync_update_cannot_move_a_task_into_another_household(client, outsider_headers):
    from app.models.task import Task
    task_id = str(uuid.uuid4())
    body = await _sync(client, outsider_headers, OUTSIDER_DEVICE, [_op("tasks", "INSERT", task_id, {
        "title": "Injected", "assigned_to_ids": [OUTSIDER_ID],
    })])
    assert len(body["applied"]) == 1, body

    await _sync(client, outsider_headers, OUTSIDER_DEVICE, [_op("tasks", "UPDATE", task_id, {
        "foyer_id": TEST_FOYER_ID, "version": 999, "title": "Injected, renamed",
    })])

    task = await _row(Task, task_id)
    assert str(task.foyer_id) == OUTSIDER_FOYER_ID
    assert task.version != 999
    assert task.title == "Injected, renamed"


@pytest.mark.asyncio
async def test_sync_update_refuses_points_out_of_bounds(client, outsider_headers):
    from app.models.task import Task
    task_id = str(uuid.uuid4())
    await _sync(client, outsider_headers, OUTSIDER_DEVICE, [_op("tasks", "INSERT", task_id, {
        "title": "Weighted", "assigned_to_ids": [OUTSIDER_ID],
    })])
    body = await _sync(client, outsider_headers, OUTSIDER_DEVICE, [_op("tasks", "UPDATE", task_id, {
        "weight": 99,
    })])
    assert len(body["conflicts"]) == 1, body
    assert (await _row(Task, task_id)).weight == 1


@pytest.mark.asyncio
async def test_sync_cannot_assign_a_member_of_another_household(client, outsider_headers):
    from app.models.task import Task
    task_id = str(uuid.uuid4())
    body = await _sync(client, outsider_headers, OUTSIDER_DEVICE, [_op("tasks", "INSERT", task_id, {
        "title": "Assigned outside", "assigned_to_ids": [TEST_USER_ID],
    })])
    assert len(body["conflicts"]) == 1, body
    assert await _row(Task, task_id) is None


@pytest.mark.asyncio
async def test_sync_cannot_complete_another_households_task(client, victim_headers, outsider_headers):
    from app.models.completion import TaskCompletion
    task_id = await _victim_task(client, victim_headers, "Victim task, complete")
    comp_id = str(uuid.uuid4())
    body = await _sync(client, outsider_headers, OUTSIDER_DEVICE, [_op("task_completions", "INSERT", comp_id, {
        "task_id": task_id, "completed_by": OUTSIDER_ID,
    })])
    assert len(body["conflicts"]) == 1, body
    assert await _row(TaskCompletion, comp_id) is None


@pytest.mark.asyncio
async def test_sync_cannot_delete_another_households_completion(client, victim_headers, outsider_headers):
    from app.models.completion import TaskCompletion
    task_id = await _victim_task(client, victim_headers, "Victim task, delete completion")
    comp_id = await _victim_completion(client, victim_headers, task_id)
    await _sync(client, outsider_headers, OUTSIDER_DEVICE, [_op("task_completions", "DELETE", comp_id)])
    assert (await _row(TaskCompletion, comp_id)).deleted_at is None


@pytest.mark.asyncio
async def test_sync_cannot_credit_a_completion_to_a_non_member(client, outsider_headers):
    from app.models.completion import TaskCompletion
    task_id = str(uuid.uuid4())
    await _sync(client, outsider_headers, OUTSIDER_DEVICE, [_op("tasks", "INSERT", task_id, {
        "title": "Own task", "assigned_to_ids": [OUTSIDER_ID],
    })])
    comp_id = str(uuid.uuid4())
    body = await _sync(client, outsider_headers, OUTSIDER_DEVICE, [_op("task_completions", "INSERT", comp_id, {
        "task_id": task_id, "completed_by": TEST_USER_ID,
    })])
    assert len(body["conflicts"]) == 1, body
    assert await _row(TaskCompletion, comp_id) is None


@pytest.mark.asyncio
async def test_fair_share_config_cannot_target_another_households_member(client, outsider_headers):
    from app.database import async_session
    from app.models.fair_share import FairShareConfig

    # The victim's ratio exists BEFORE the attack: the old route looked it up by user_id
    # alone and overwrote it. Without this row it crashed on NOT NULL instead, a red that
    # proved nothing about the attack.
    async with async_session() as session:
        found = await session.execute(select(FairShareConfig).where(
            FairShareConfig.user_id == uuid.UUID(TEST_USER_ID),
            FairShareConfig.foyer_id == uuid.UUID(TEST_FOYER_ID),
        ))
        cfg = found.scalar_one_or_none()
        if cfg is None:
            session.add(FairShareConfig(
                user_id=uuid.UUID(TEST_USER_ID), foyer_id=uuid.UUID(TEST_FOYER_ID), target_ratio=0.5,
            ))
        else:
            cfg.target_ratio = 0.5
        await session.commit()

    r = await client.put("/api/fair-share/config", headers=outsider_headers,
                         json=[{"user_id": TEST_USER_ID, "target_ratio": 0.99}])

    async with async_session() as session:
        ratios = (await session.execute(select(FairShareConfig.target_ratio).where(
            FairShareConfig.user_id == uuid.UUID(TEST_USER_ID),
        ))).scalars().all()
    assert 0.99 not in ratios, ratios
    assert r.status_code == 400, r.text


@pytest.mark.asyncio
async def test_ratings_of_another_households_task_are_not_readable(client, victim_headers, outsider_headers):
    task_id = await _victim_task(client, victim_headers, "Victim task, ratings")
    r1 = await client.get(f"/api/tasks/{task_id}/ratings", headers=outsider_headers)
    r2 = await client.get(f"/api/tasks/{task_id}/rating-context", headers=outsider_headers)
    assert (r1.status_code, r2.status_code) == (404, 404), (r1.text, r2.text)
    own = await client.get(f"/api/tasks/{task_id}/ratings", headers=victim_headers)
    assert own.status_code == 200, own.text


@pytest.mark.asyncio
async def test_history_does_not_leak_another_households_task_title(client, victim_headers, outsider_headers):
    title = f"Secret title {uuid.uuid4().hex[:8]}"
    task_id = await _victim_task(client, victim_headers, title)
    await _sync(client, outsider_headers, OUTSIDER_DEVICE, [_op("tasks", "DELETE", task_id)])
    r = await client.get("/api/history?limit=200", headers=outsider_headers)
    assert r.status_code == 200, r.text
    assert title not in r.text
