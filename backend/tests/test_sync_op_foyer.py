"""Each /sync op applies in the household it was queued in (SyncOperation.foyer_id).

Paths, and what covers each:
- op tagged with a household of the caller, token naming ANOTHER one — the task lands in the
  tagged household (the defect: it landed in the token's);
- op without the field (older apps) — token's household, unchanged behaviour;
- op tagged with a household the caller does not belong to — `out_of_scope`, nothing written;
- UPDATE of a task that does not exist in the household — `out_of_scope`, so the client drops it
  instead of replaying a `stale_update` forever.
Not covered: completions follow the same `_op_foyer` resolution and are covered for scope by
test_foyer_isolation.py; the client side (Room column, dropping `out_of_scope`) is Android.

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

SECOND_FOYER_ID = "5ec0d000-0000-4000-8000-0000000f0e02"
STRANGER_FOYER_ID = "5ec0d000-0000-4000-8000-0000000f0e03"


def _op(operation, row_id, data=None, foyer_id=None):
    op = {
        "client_op_id": str(uuid.uuid4()),
        "table": "tasks",
        "operation": operation,
        "row_id": row_id,
        "data": data,
        "client_timestamp": datetime.now(timezone.utc).isoformat(),
    }
    if foyer_id is not None:
        op["foyer_id"] = foyer_id
    return op


@pytest_asyncio.fixture(scope="module", autouse=True)
async def seed_second_household():
    """The test user also belongs to SECOND; STRANGER exists without them."""
    from app.database import async_session
    from app.models.foyer import Foyer
    from app.models.user_foyer import UserFoyer

    async with async_session() as session:
        for fid, name in ((SECOND_FOYER_ID, "Second household"), (STRANGER_FOYER_ID, "Stranger")):
            if await session.get(Foyer, uuid.UUID(fid)) is None:
                session.add(Foyer(id=uuid.UUID(fid), name=name))
        await session.flush()
        found = await session.execute(select(UserFoyer).where(
            UserFoyer.user_id == uuid.UUID(TEST_USER_ID),
            UserFoyer.foyer_id == uuid.UUID(SECOND_FOYER_ID),
        ))
        if found.scalar_one_or_none() is None:
            session.add(UserFoyer(
                user_id=uuid.UUID(TEST_USER_ID), foyer_id=uuid.UUID(SECOND_FOYER_ID), role="admin",
            ))
        await session.commit()
    yield


@pytest.fixture(scope="module")
def second_headers():
    """Token naming SECOND: the user has just switched households."""
    return {"Authorization": f"Bearer {create_token(TEST_USER_ID, DEVICE_A, foyer_id=SECOND_FOYER_ID)}"}


async def _sync(client, headers, ops):
    r = await client.post("/api/sync", headers=headers, json={
        "device_id": DEVICE_A, "last_server_version": 0, "changes": ops,
    })
    assert r.status_code == 200, r.text
    return r.json()


async def _task_foyer(task_id):
    from app.database import async_session
    from app.models.task import Task
    async with async_session() as session:
        task = await session.get(Task, uuid.UUID(task_id))
        return None if task is None else str(task.foyer_id)


@pytest.mark.asyncio
async def test_op_queued_in_a_household_applies_there_after_a_switch(client, second_headers):
    task_id = str(uuid.uuid4())
    body = await _sync(client, second_headers, [_op("INSERT", task_id, {
        "title": "Queued in the first household", "assigned_to_ids": [TEST_USER_ID],
    }, foyer_id=TEST_FOYER_ID)])
    assert len(body["applied"]) == 1, body
    assert await _task_foyer(task_id) == TEST_FOYER_ID


@pytest.mark.asyncio
async def test_untagged_op_applies_in_the_tokens_household(client, second_headers):
    task_id = str(uuid.uuid4())
    body = await _sync(client, second_headers, [_op("INSERT", task_id, {
        "title": "Untagged", "assigned_to_ids": [TEST_USER_ID],
    })])
    assert len(body["applied"]) == 1, body
    assert await _task_foyer(task_id) == SECOND_FOYER_ID


@pytest.mark.asyncio
async def test_op_tagged_with_a_foreign_household_is_out_of_scope(client, second_headers):
    task_id = str(uuid.uuid4())
    body = await _sync(client, second_headers, [_op("INSERT", task_id, {
        "title": "Smuggled", "assigned_to_ids": [TEST_USER_ID],
    }, foyer_id=STRANGER_FOYER_ID)])
    assert [c["reason"] for c in body["conflicts"]] == ["out_of_scope"], body
    assert await _task_foyer(task_id) is None


@pytest.mark.asyncio
async def test_update_of_a_missing_task_is_out_of_scope(client, second_headers):
    body = await _sync(client, second_headers, [_op("UPDATE", str(uuid.uuid4()), {"title": "x"})])
    assert [c["reason"] for c in body["conflicts"]] == ["out_of_scope"], body
