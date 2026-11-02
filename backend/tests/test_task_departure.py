"""A member's tasks when they leave the household, come back, and are purged.

Rule set by Quentin on 2026-11-02 (app/services/task_departure.py). B leaves a household where:
- SHARED is assigned to A and B: B is removed from it, A can still edit it;
- SOLO is assigned to B alone, active: it is deactivated and marked `suspended_at`;
- PAUSED is assigned to B alone, deactivated by hand earlier: it is left as it was, unmarked.
Then:
- B comes back: SOLO is active again, PAUSED stays inactive;
- B's account is purged (30 days after DELETE /me): SOLO and PAUSED are deleted, SHARED stays.

Before 2026-11-02 B stayed on SHARED, and an edit of SHARED sent from the app (which sends the
whole assignee list back) was refused for good: `test_the_shared_task_loses_only_the_leaver`
is red on that code.

PATHS by which a member stops, or starts again, being an active member (writers of
`user_foyers.left_at`, grepped on 2026-11-02):
  (1) POST /foyers/{id}/leave, through soft_leave_foyer ........................... COVERED
  (2) DELETE /me, through soft_leave_foyer for each household ..................... COVERED
  (3) the restore of recovery.py, which clears left_at: its call to
      reactivate_suspended_tasks is COVERED at service level; the route itself is not
      (it only rejoins the user's last active household, which /leave clears when they had
      no other: unreachable in that common case, said in the report of 2026-11-02).
  (4) the purge of cleanup.py (users deleted for more than 30 days): its task step,
      delete_tasks_only_assigned_to, is COVERED.
non couvert : purge_deleted_users de bout en bout, parce qu'elle échoue avant ce test (mesuré le
2026-11-02) : six clés étrangères vers users sans ON DELETE refusent la suppression d'un compte
qui a agi (todo.md, « La purge des comptes supprimés échoue dès qu'un compte a agi »).
Not a path: a QR join always creates a NEW account, it never revives a former member.
non couvert : la route POST /recovery/restore elle-même, parce qu'elle ne réintègre que le
dernier foyer actif, que /leave efface quand il n'y en a pas d'autre (cas courant inatteignable).
"""

from __future__ import annotations

import uuid

import pytest
import pytest_asyncio
from sqlalchemy import select

from app.auth import create_token


def _headers(user_id: uuid.UUID, foyer_id: uuid.UUID) -> dict:
    token = create_token(str(user_id), str(uuid.uuid4()), foyer_id=str(foyer_id))
    return {"Authorization": f"Bearer {token}"}


@pytest_asyncio.fixture
async def house():
    """A fresh household with A, B and the three tasks; ids returned in a dict."""
    from app.database import async_session
    from app.models.foyer import Foyer
    from app.models.task import Task
    from app.models.task_assignment import TaskAssignment
    from app.models.user import User
    from app.models.user_foyer import UserFoyer

    ids = {k: uuid.uuid4() for k in ("foyer", "a", "b", "shared", "solo", "paused")}
    async with async_session() as session:
        session.add(Foyer(id=ids["foyer"], name="Departure household"))
        await session.flush()
        for who in ("a", "b"):
            session.add(User(id=ids[who], username=f"dep-{ids[who].hex[:10]}",
                             display_name=who.upper(), last_active_foyer_id=ids["foyer"]))
        await session.flush()
        for who in ("a", "b"):
            session.add(UserFoyer(user_id=ids[who], foyer_id=ids["foyer"], role="admin"))
        for key, active, assignees in (("shared", True, ("a", "b")), ("solo", True, ("b",)),
                                       ("paused", False, ("b",))):
            session.add(Task(id=ids[key], foyer_id=ids["foyer"], title=f"dep {key}",
                             is_active=active))
            await session.flush()
            for who in assignees:
                session.add(TaskAssignment(task_id=ids[key], user_id=ids[who]))
        await session.commit()
    return ids


async def _state(task_id: uuid.UUID):
    """(is_active, suspended_at, sorted assignee ids) of a task, or None if it is gone."""
    from app.database import async_session
    from app.models.task import Task
    from app.models.task_assignment import TaskAssignment

    async with async_session() as session:
        task = await session.get(Task, task_id)
        if task is None:
            return None
        rows = await session.execute(
            select(TaskAssignment.user_id).where(TaskAssignment.task_id == task_id))
        return task.is_active, task.suspended_at, sorted(str(u) for u in rows.scalars())


async def _leave(client, ids):
    r = await client.post(f"/api/foyers/{ids['foyer']}/leave",
                          headers=_headers(ids["b"], ids["foyer"]))
    assert r.status_code == 200, r.text


@pytest.mark.asyncio
async def test_the_shared_task_loses_only_the_leaver(client, house):
    assert (await _state(house["shared"]))[2] == sorted([str(house["a"]), str(house["b"])])
    await _leave(client, house)

    active, suspended, assignees = await _state(house["shared"])
    assert assignees == [str(house["a"])]
    assert active is True and suspended is None

    # What the app does on "Enregistrer": the whole assignee list it knows, sent back.
    r = await client.put(f"/api/tasks/{house['shared']}", headers=_headers(house["a"], house["foyer"]),
                         json={"description": "edited after B left",
                               "assigned_to_ids": [str(house["a"])]})
    assert r.status_code == 200, r.text


@pytest.mark.asyncio
async def test_the_change_reaches_the_other_phones(client, house):
    from app.database import async_session
    from app.models.sync import ChangeLog

    await _leave(client, house)
    async with async_session() as session:
        rows = (await session.execute(select(ChangeLog).where(
            ChangeLog.row_id.in_([house["shared"], house["solo"]]),
            ChangeLog.table_name == "tasks",
        ))).scalars().all()
    by_row = {r.row_id: r.new_data for r in rows}
    assert by_row[house["shared"]] == {"assigned_to_ids": [str(house["a"])]}
    assert by_row[house["solo"]] == {"is_active": False}


@pytest.mark.asyncio
async def test_solo_tasks_are_suspended_and_come_back(client, house):
    from app.database import async_session
    from app.services.task_departure import reactivate_suspended_tasks

    await _leave(client, house)
    active, suspended, assignees = await _state(house["solo"])
    assert active is False and suspended is not None and assignees == [str(house["b"])]
    paused = await _state(house["paused"])
    assert paused[0] is False and paused[1] is None

    async with async_session() as session:
        await reactivate_suspended_tasks(session, house["b"], house["foyer"])
        await session.commit()
    active, suspended, _ = await _state(house["solo"])
    assert active is True and suspended is None
    assert (await _state(house["paused"]))[0] is False


@pytest.mark.asyncio
async def test_deleting_the_account_detaches_too(client, house):
    r = await client.delete("/api/me", headers=_headers(house["b"], house["foyer"]))
    assert r.status_code == 204, r.text
    assert (await _state(house["shared"]))[2] == [str(house["a"])]
    assert (await _state(house["solo"]))[0] is False


@pytest.mark.asyncio
async def test_the_purge_deletes_the_solo_tasks_only(client, house):
    """The step the purge runs before deleting the users (services/cleanup.py)."""
    from app.database import async_session
    from app.services.task_departure import delete_tasks_only_assigned_to

    await _leave(client, house)
    async with async_session() as session:
        assert await delete_tasks_only_assigned_to(session, [house["b"]]) == 2
        await session.commit()

    assert await _state(house["solo"]) is None
    assert await _state(house["paused"]) is None
    assert (await _state(house["shared"]))[2] == [str(house["a"])]
