"""A task save writes, and journals, only the fields that really change.

Measured on the beta 2026-11-20: every edit line listed « (titre, poids, tolérance, contextes,
attribution) » and the detail said « a modifié 5 champs », even for a save that changed
nothing: change_log rows with identical `new_data` and `old_data` NULL. The app sends every
field on each save (older APKs in service keep doing so), so the server must diff.

PATHS that write a task UPDATE from a client payload (grep `record_change(` on "tasks"):
  (a) PUT /tasks/{id} ........................................ COVERED, parametrised `rest`
  (b) /sync UPDATE (`_handle_task`) .......................... COVERED, parametrised `sync`
  (c) services written by the server itself (departure, archiving, frequency vote) write
      a single key they know changes: not a client payload, not touched.
  non couvert : services/task_departure.py, services/archiving.py, api/gamification.py, a
      single key the server itself decides, never a client payload, unchanged by this work.
  non couvert : INSERT of a task (REST and /sync), the journal of a creation is the whole
      task by design (`task_create`), no previous value exists.
  non couvert : PUT /tasks/{id} returning to a votable recurrence type from a non-votable one
      (the vote helper writes the rule, guarded by test_frequency_vote_guard.py), run by the
      full suite.
  non couvert : two simultaneous saves of one task (the diff reads the row without a lock; the
      worst case is one redundant journal row, as before).
Rules checked: no-op = no change_log row, no version bump, still acknowledged (not `dropped`);
one changed field = one key in new_data/old_data and one label in both history texts; a
reorder of the contexts' tail or of the assignees = no-op, a new PRIMARY context = a change;
a no-op does not make a later op of another device stale (D2); rows written before this
change (every field, old_data NULL) still render as they did.
"""

from __future__ import annotations

import asyncio
import uuid
from datetime import datetime

import pytest
from sqlalchemy import func, select

from app.database import async_session
from app.models.sync import ChangeLog
from app.models.task import Task
from tests.module_helpers import House, house

PATHS = ["rest", "sync"]


async def _context(client, h: House, name: str) -> str:
    resp = await client.post("/api/contexts", headers=h.headers(0), json={"name": name})
    assert resp.status_code in (200, 201), resp.text
    return resp.json()["id"]


async def _task(client, h: House, contexts: list[str]) -> str:
    resp = await client.post(
        "/api/tasks", headers=h.headers(0),
        json={"title": "Plantes", "description": "arroser", "weight": 3,
              "assigned_to_ids": [str(u) for u in h.user_ids], "context_ids": contexts,
              "recurrence_rule": {"type": "interval", "every_n_days": 7}},
    )
    assert resp.status_code in (200, 201), resp.text
    return resp.json()["id"]


def _full(h: House, contexts: list[str], **overrides) -> dict:
    """The payload an app sends on every save: all fields, here equal to the task's state."""
    payload = {
        "title": "Plantes", "description": "arroser", "weight": 3, "tolerance": "NORMAL",
        "is_active": True, "recurrence_rule": {"type": "interval", "every_n_days": 7},
        "assigned_to_ids": [str(u) for u in h.user_ids], "context_ids": list(contexts),
    }
    payload.update(overrides)
    return payload


async def _save(
    client, h: House, path: str, task_id: str, payload: dict, *, device: str | None = None,
    timestamp: str = "2031-01-01T09:00:00+00:00", member: int = 0,
) -> dict:
    """One save by `path`; the answer's facts: `conflicts` and `dropped` (sync only)."""
    if path == "rest":
        resp = await client.put(f"/api/tasks/{task_id}", headers=h.headers(member), json=payload)
        assert resp.status_code == 200, resp.text
        return {"conflicts": [], "dropped": False}
    body = {"device_id": device or str(uuid.uuid4()), "last_server_version": 0, "changes": [{
        "client_op_id": str(uuid.uuid4()), "table": "tasks", "operation": "UPDATE",
        "row_id": task_id, "data": payload, "client_timestamp": timestamp}]}
    resp = await client.post("/api/sync", headers=h.headers(member), json=body)
    assert resp.status_code == 200, resp.text
    answer = resp.json()
    assert len(answer["applied"]) + len(answer["conflicts"]) == 1, answer
    return {"conflicts": answer["conflicts"],
            "dropped": bool(answer["applied"] and answer["applied"][0]["dropped"])}


async def _updates(task_id: str) -> list[ChangeLog]:
    """The UPDATE rows of the task, oldest first, with the creator's frequency vote's own."""
    async with async_session() as session:
        rows = await session.execute(
            select(ChangeLog).where(
                ChangeLog.table_name == "tasks", ChangeLog.operation == "UPDATE",
                ChangeLog.row_id == uuid.UUID(task_id),
            ).order_by(ChangeLog.server_version)
        )
        return list(rows.scalars().all())


async def _version(task_id: str) -> int:
    async with async_session() as session:
        return (await session.execute(
            select(Task.version).where(Task.id == uuid.UUID(task_id))
        )).scalar_one()


async def _task_history(client, h: House, task_id: str) -> list[dict]:
    resp = await client.get(f"/api/tasks/{task_id}/history", headers=h.headers(0))
    assert resp.status_code == 200, resp.text
    return resp.json()


async def _feed_updates(client, h: House, task_id: str) -> list[dict]:
    resp = await client.get("/api/history", headers=h.headers(0))
    assert resp.status_code == 200, resp.text
    return [e for e in resp.json()
            if e["type"] == "task_updated" and e["task"] and e["task"]["id"] == task_id]


@pytest.mark.asyncio
@pytest.mark.parametrize("path", PATHS)
async def test_a_save_that_changes_nothing_leaves_no_trace(client, path):
    async with house() as h:
        contexts = [await _context(client, h, "Salon"), await _context(client, h, "Balcon")]
        task_id = await _task(client, h, contexts)
        rows_before, version_before = await _updates(task_id), await _version(task_id)
        feed_before = await _feed_updates(client, h, task_id)
        history_before = await _task_history(client, h, task_id)

        # Same state, assignees in the other order.
        payload = _full(h, contexts, assigned_to_ids=[str(u) for u in reversed(h.user_ids)])
        answer = await _save(client, h, path, task_id, payload)

        assert answer["conflicts"] == [] and answer["dropped"] is False
        assert len(await _updates(task_id)) == len(rows_before)
        assert await _version(task_id) == version_before
        assert await _feed_updates(client, h, task_id) == feed_before == []
        assert await _task_history(client, h, task_id) == history_before


@pytest.mark.asyncio
@pytest.mark.parametrize("path", PATHS)
async def test_a_title_change_journals_that_field_alone(client, path):
    async with house() as h:
        contexts = [await _context(client, h, "Salon")]
        task_id = await _task(client, h, contexts)
        rows_before = await _updates(task_id)

        await _save(client, h, path, task_id, _full(h, contexts, title="Plantes vertes"))

        rows = await _updates(task_id)
        assert len(rows) == len(rows_before) + 1, [r.new_data for r in rows]
        assert rows[-1].new_data == {"title": "Plantes vertes"}
        assert rows[-1].old_data == {"title": "Plantes"}

        [edit] = [e for e in await _task_history(client, h, task_id)
                  if e["event_type"] == "task_update"]
        assert edit["details"]["fields_changed"] == ["title"]
        assert edit["details"]["old"] == {"title": "Plantes"}
        [line] = await _feed_updates(client, h, task_id)
        assert line["meta"]["fields"] == ["title"]
        assert "(titre)" in line["description"], line["description"]
        assert "poids" not in line["description"] and "attribution" not in line["description"]


@pytest.mark.asyncio
@pytest.mark.parametrize("path", PATHS)
async def test_several_changes_list_exactly_those_fields(client, path):
    async with house() as h:
        contexts = [await _context(client, h, "Salon")]
        task_id = await _task(client, h, contexts)

        await _save(client, h, path, task_id, _full(h, contexts, weight=5, tolerance="STRICT"))

        row = (await _updates(task_id))[-1]
        assert row.new_data == {"weight": 5, "tolerance": "STRICT"}, row.new_data
        assert row.old_data == {"weight": 3, "tolerance": "NORMAL"}, row.old_data
        [line] = await _feed_updates(client, h, task_id)
        assert set(line["meta"]["fields"]) == {"weight", "tolerance"}


@pytest.mark.asyncio
@pytest.mark.parametrize("path", PATHS)
async def test_a_reorder_of_the_contexts_tail_is_no_change_a_new_primary_is(client, path):
    async with house() as h:
        a, b, c = [await _context(client, h, n) for n in ("Salon", "Balcon", "Cave")]
        task_id = await _task(client, h, [a, b, c])
        rows_before = await _updates(task_id)

        await _save(client, h, path, task_id, _full(h, [a, c, b]))
        assert len(await _updates(task_id)) == len(rows_before), "tail reorder journalled"

        # The primary context decides where the task is displayed: that one is a real edit.
        await _save(client, h, path, task_id, _full(h, [b, a, c]))
        row = (await _updates(task_id))[-1]
        assert row.new_data == {"context_ids": [b, a, c]}, row.new_data
        assert row.old_data == {"context_ids": [a, b, c]}, row.old_data


@pytest.mark.asyncio
@pytest.mark.parametrize("path", PATHS)
async def test_changed_assignees_and_recurrence_carry_their_previous_values(client, path):
    async with house() as h:
        task_id = await _task(client, h, [])
        mine = str(h.user_ids[0])

        await _save(client, h, path, task_id, _full(
            h, [], assigned_to_ids=[mine], recurrence_rule={"type": "uncertain"},
        ))

        row = (await _updates(task_id))[-1]
        assert row.new_data["assigned_to_ids"] == [mine]
        assert set(row.old_data["assigned_to_ids"]) == {str(u) for u in h.user_ids}
        assert row.new_data["recurrence_rule"] == {"type": "uncertain"}
        assert row.old_data["recurrence_rule"] == {"type": "interval", "every_n_days": 7}
        assert set(row.new_data) == {"assigned_to_ids", "recurrence_rule"}


@pytest.mark.asyncio
async def test_sync_acknowledges_a_no_op_and_replays_it_the_same(client):
    """`dropped` stays false: the row exists and already holds what the client has."""
    async with house() as h:
        task_id = await _task(client, h, [])
        op = {"client_op_id": str(uuid.uuid4()), "table": "tasks", "operation": "UPDATE",
              "row_id": task_id, "data": _full(h, []),
              "client_timestamp": "2031-01-01T09:00:00+00:00"}
        body = {"device_id": str(uuid.uuid4()), "last_server_version": 0, "changes": [op]}
        first = (await client.post("/api/sync", headers=h.headers(0), json=body)).json()
        replay = (await client.post("/api/sync", headers=h.headers(0), json=body)).json()
        for answer in (first, replay):
            assert answer["conflicts"] == [] and len(answer["applied"]) == 1, answer
            assert answer["applied"][0]["dropped"] is False
            assert answer["applied"][0]["server_row_id"] == task_id


@pytest.mark.asyncio
async def test_a_no_op_does_not_make_another_device_look_stale(client):
    """D2: device B queued its edit BEFORE device A's no-op save reached the server."""
    async with house() as h:
        task_id = await _task(client, h, [])
        async with async_session() as session:
            queued_by_b = (await session.execute(select(func.clock_timestamp()))).scalar()
        await asyncio.sleep(0.1)
        await _save(client, h, "sync", task_id, _full(h, []), device=str(uuid.uuid4()))
        await asyncio.sleep(0.1)

        answer = await _save(
            client, h, "sync", task_id, {"title": "Plantes B"}, device=str(uuid.uuid4()),
            timestamp=_iso(queued_by_b), member=1,
        )

        assert answer["conflicts"] == [], answer
        assert (await _updates(task_id))[-1].new_data == {"title": "Plantes B"}


def _iso(dt: datetime) -> str:
    return dt.isoformat()


@pytest.mark.asyncio
async def test_rows_written_before_the_change_render_as_they_did(client):
    """Every field, `old_data` NULL: the old shape. Its keys are listed, nothing is hidden."""
    from app.utils.changelog import record_change

    async with house() as h:
        task_id = await _task(client, h, [])
        legacy = _full(h, [])
        del legacy["context_ids"]
        async with async_session() as session:
            await record_change(
                session, "tasks", uuid.UUID(task_id), "UPDATE", str(h.user_ids[0]),
                foyer_id=h.foyer_id, new_data=legacy,
            )
            await session.commit()

        [edit] = [e for e in await _task_history(client, h, task_id)
                  if e["event_type"] == "task_update" and e["details"]["old"] == {}]
        assert set(edit["details"]["fields_changed"]) == set(legacy)
        [line] = await _feed_updates(client, h, task_id)
        assert set(line["meta"]["fields"]) == set(legacy)
        assert line["description"].count(",") == len(legacy) - 1, line["description"]
