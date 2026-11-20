"""Two transactions writing the change log at the same moment must both commit.

`record_change` numbers a row `MAX(server_version) + 1`, and `change_log.server_version` is
UNIQUE. Two transactions reading the same maximum computed the same number; the second blocked
on the unique index until the first committed, then failed: a 500 for a plain vote
(`change_log_server_version_key`, measured 2026-11-20: two members voting the effort of one
task together, 9 rounds out of 10). Any two writers hit it, whatever the rows they write.

Invariant: every committed change carries a UNIQUE version, and a client that pulled before
the race receives EVERY change of the race (the cursor of `_pull_changes` assumes no lower
version commits after a higher one has been delivered).

PATHS that write change_log (grep `record_change(` in app/): all of them go through the one
function, so the cases below pick two shapes of caller, not every route:
  (1) a REST route (PUT /tasks/{id}/effort), two DIFFERENT members, on the SAME task (they
      share the votes lock, so this one also pins the consensus: weight == mean) and on
      DIFFERENT tasks (no lock in common: only the version retry saves it) ......... COVERED
  (2) /sync, two devices, two different ops ............................ COVERED
  (3) the retry bound: a caller that fails 5 times in a row re-raises .. COVERED (unit)
  (4) an error of another constraint inside the savepoint .............. COVERED (unit):
      re-raised at once, not retried
  (5) what the caller wrote before `record_change` survives a retry .... COVERED (a vote's
      `task.weight` is written before it, and is read back after the race)
  not covered: the other ~40 routes, same function, same retry.
"""

from __future__ import annotations

import asyncio
import uuid

import pytest
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError

from tests.module_helpers import house
from tests.test_replayed_writes_race import _new_task

ROUNDS = 10


async def _max_version() -> int:
    from app.database import async_session
    from app.models.sync import ChangeLog

    async with async_session() as session:
        return (await session.execute(select(func.max(ChangeLog.server_version)))).scalar() or 0


async def _versions_after(floor: int, table: str, row_ids: set) -> list[int]:
    from app.database import async_session
    from app.models.sync import ChangeLog

    async with async_session() as session:
        rows = (await session.execute(
            select(ChangeLog.server_version).where(
                ChangeLog.server_version > floor, ChangeLog.table_name == table,
                ChangeLog.row_id.in_(row_ids),
            )
        )).scalars().all()
    return list(rows)


async def _pull(client, headers, since: int) -> list[dict]:
    body = {"device_id": str(uuid.uuid4()), "last_server_version": since, "changes": []}
    resp = await client.post("/api/sync", headers=headers, json=body)
    assert resp.status_code == 200, resp.text
    return resp.json()["server_changes"]


@pytest.mark.asyncio
async def test_two_members_voting_together_all_succeed_with_unique_versions(client):
    from app.database import async_session
    from app.models.task import Task
    from app.models.task_assignment import TaskAssignment

    async with house() as h:
        for _ in range(ROUNDS):
            task_id = await _new_task(h)
            async with async_session() as session:
                session.add(TaskAssignment(task_id=task_id, user_id=h.user_ids[1]))
                await session.commit()
            floor = await _max_version()
            url = f"/api/tasks/{task_id}/effort"
            a, b = await asyncio.gather(
                client.put(url, headers=h.headers(0), json={"points": 3}),
                client.put(url, headers=h.headers(1), json={"points": 7}),
            )
            assert (a.status_code, b.status_code) == (200, 200), (a.text[:200], b.text[:200])

            # Two VOTE lines, two distinct versions (UNIQUE makes duplicates impossible; the
            # count says neither line was lost).
            versions = await _versions_after(floor, "task_efforts", {task_id})
            assert len(versions) == 2 and len(set(versions)) == 2, versions
            # Every change of the race reaches a client that pulled before it.
            pulled = [c for c in await _pull(client, h.headers(0), floor)
                      if c["table"] == "task_efforts" and c["row_id"] == str(task_id)]
            assert sorted(c["server_version"] for c in pulled) == sorted(versions)
            # What the route wrote before the change log survives the retry: the consensus is
            # the rounded mean of the two votes, whichever one lost the race.
            async with async_session() as session:
                weight = (await session.execute(
                    select(Task.weight).where(Task.id == task_id))).scalar()
            assert weight == 5, weight


@pytest.mark.asyncio
async def test_two_members_voting_on_different_tasks_together_all_succeed(client):
    """No lock in common (votes lock per task): only the version retry can save this one."""
    from app.database import async_session
    from app.models.task import Task
    from app.models.task_assignment import TaskAssignment
    from app.models.task_effort import TaskEffort

    async with house() as h:
        for _ in range(ROUNDS):
            t1, t2 = await _new_task(h), await _new_task(h)
            async with async_session() as session:
                session.add(TaskAssignment(task_id=t2, user_id=h.user_ids[1]))
                await session.commit()
            floor = await _max_version()
            a, b = await asyncio.gather(
                client.put(f"/api/tasks/{t1}/effort", headers=h.headers(0), json={"points": 3}),
                client.put(f"/api/tasks/{t2}/effort", headers=h.headers(1), json={"points": 7}),
            )
            assert (a.status_code, b.status_code) == (200, 200), (a.text[:200], b.text[:200])
            versions = await _versions_after(floor, "task_efforts", {t1, t2})
            assert len(versions) == 2 and len(set(versions)) == 2, versions
            pulled = [c for c in await _pull(client, h.headers(0), floor)
                      if c["table"] == "task_efforts"]
            assert sorted(c["server_version"] for c in pulled) == sorted(versions)
            # The retry rolled back only the INSERT: the weight each route wrote BEFORE the
            # change log (flushed outside the savepoint) and the vote row are still there.
            async with async_session() as session:
                weights = {t: (await session.execute(
                    select(Task.weight).where(Task.id == t))).scalar() for t in (t1, t2)}
                votes = {t: (await session.execute(
                    select(TaskEffort.points).where(TaskEffort.task_id == t))).scalar()
                    for t in (t1, t2)}
            assert weights == {t1: 3, t2: 7} and votes == {t1: 3, t2: 7}, (weights, votes)


@pytest.mark.asyncio
async def test_two_devices_syncing_different_ops_together_both_apply(client):
    """Two /sync calls, each with one op on its own task; neither may end as a conflict."""
    from app.database import async_session
    from app.models.task import Task
    from app.models.task_assignment import TaskAssignment

    def op(task_id, title):
        return {
            "client_op_id": str(uuid.uuid4()), "table": "tasks", "operation": "UPDATE",
            "row_id": str(task_id), "data": {"title": title},
            "client_timestamp": "2031-01-01T09:00:00+00:00",
        }

    async with house() as h:
        for i in range(ROUNDS):
            t1, t2 = await _new_task(h), await _new_task(h)
            # Two assignees make the tasks collective: a solo task is hidden from the other member.
            async with async_session() as session:
                for t in (t1, t2):
                    session.add(TaskAssignment(task_id=t, user_id=h.user_ids[1]))
                await session.commit()
            floor = await _max_version()
            bodies = [
                {"device_id": str(uuid.uuid4()), "last_server_version": floor,
                 "changes": [op(t, f"renamed {i} {n}")]}
                for n, t in enumerate((t1, t2))
            ]
            ra, rb = await asyncio.gather(
                client.post("/api/sync", headers=h.headers(0), json=bodies[0]),
                client.post("/api/sync", headers=h.headers(1), json=bodies[1]),
            )
            assert (ra.status_code, rb.status_code) == (200, 200), (ra.text[:200], rb.text[:200])
            for r in (ra, rb):
                assert r.json()["conflicts"] == [], r.text[:400]
                assert len(r.json()["applied"]) == 1
            versions = await _versions_after(floor, "tasks", {t1, t2})
            assert len(versions) == 2 and len(set(versions)) == 2, versions
            async with async_session() as session:
                titles = {t: (await session.execute(
                    select(Task.title).where(Task.id == t))).scalar() for t in (t1, t2)}
            assert titles == {t1: f"renamed {i} 0", t2: f"renamed {i} 1"}


class _Collision(IntegrityError):
    """An IntegrityError whose driver message names a constraint, as asyncpg's does."""

    def __init__(self, constraint: str):
        super().__init__("INSERT ...", {}, Exception(
            f'duplicate key value violates unique constraint "{constraint}"'))


@pytest.mark.asyncio
async def test_record_change_gives_up_after_its_bound_and_ignores_other_constraints(monkeypatch):
    from app.database import async_session
    from app.utils import changelog

    async with house() as h:
        attempts = []

        async def always_collides(self, *args, **kwargs):
            attempts.append(1)
            raise _Collision("change_log_server_version_key")

        async with async_session() as session:
            monkeypatch.setattr(type(session), "execute", always_collides)
            with pytest.raises(IntegrityError):
                await changelog.record_change(
                    session, "tasks", h.task_id, "UPDATE", str(h.user_ids[0]), foyer_id=h.foyer_id)
            assert len(attempts) == changelog.MAX_VERSION_ATTEMPTS
            monkeypatch.undo()

        attempts.clear()

        async def other_constraint(self, *args, **kwargs):
            attempts.append(1)
            raise _Collision("change_log_pkey")

        async with async_session() as session:
            monkeypatch.setattr(type(session), "execute", other_constraint)
            with pytest.raises(IntegrityError):
                await changelog.record_change(
                    session, "tasks", h.task_id, "UPDATE", str(h.user_ids[0]), foyer_id=h.foyer_id)
            assert len(attempts) == 1
            monkeypatch.undo()
