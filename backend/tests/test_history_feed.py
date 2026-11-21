"""The Stats history feed (GET /api/history) says what happened, with the words of the time.

Three defects found by the Android agent, each a way for a line to lie or to be missing:
  (1) a « modified » line printed a raw key (`context_ids`) when the field had no label;
  (2) every line of a task printed its CURRENT title, so a renamed task rewrote its past;
  (3) a member's FIRST vote never appeared (the feed kept only votes whose `updated_at` was
      later than `created_at`, i.e. votes changed at least once).

PATHS that put a field name into a task's change_log row (grep `record_change(` on "tasks"):
  (a) PUT /tasks/{id} (REST, `TaskUpdate` keys) ............................ COVERED (HTTP + keys)
  (b) /sync UPDATE: the client's payload as sent, unknown keys included .... COVERED (HTTP)
  (c) services/task_departure.py (`is_active`, `assigned_to_ids`) ......... COVERED (keys)
  (d) services/archiving.py (`is_archived`) ................................ COVERED (keys)
  (e) PUT /tasks/{id}/frequency (`recurrence_rule`) ........................ not covered: already
      labelled, and de-duplicated against its vote by the existing rule.
Votes (3): effort and affinity, first vote, replay of the same value, changed value, the
member filter; titles (2): the line of a task created, renamed, then voted.
"""

from __future__ import annotations

import asyncio
import uuid

import pytest

from tests.module_helpers import House, house


async def _create_task(client, h: House, title: str, context_ids: list[str] | None = None) -> str:
    resp = await client.post(
        "/api/tasks", headers=h.headers(0),
        json={"title": title, "assigned_to_ids": [str(u) for u in h.user_ids],
              "recurrence_rule": {"type": "interval", "every_n_days": 7},
              "context_ids": context_ids},
    )
    assert resp.status_code in (200, 201), resp.text
    return resp.json()["id"]


async def _feed(client, h: House, member: int = 0, **params) -> list[dict]:
    resp = await client.get("/api/history", headers=h.headers(member), params=params)
    assert resp.status_code == 200, resp.text
    return resp.json()


def _of_task(events: list[dict], task_id: str, kind: str) -> list[dict]:
    return [e for e in events if e["type"] == kind and e["task"] and e["task"]["id"] == task_id]


# --- (1) field labels ---------------------------------------------------------------------


@pytest.mark.asyncio
async def test_a_context_edit_is_named_not_printed_raw(client):
    async with house() as h:
        # Detaching a context from a task that has none is no edit (nothing is journalled), so
        # the task starts with one.
        ctx = await client.post("/api/contexts", headers=h.headers(0), json={"name": "Bain"})
        assert ctx.status_code in (200, 201), ctx.text
        task_id = await _create_task(client, h, "Salle de bain", [ctx.json()["id"]])
        resp = await client.put(
            f"/api/tasks/{task_id}", headers=h.headers(0), json={"context_ids": [], "weight": 4}
        )
        assert resp.status_code == 200, resp.text
        updates = _of_task(await _feed(client, h), task_id, "task_updated")
        assert len(updates) == 1, updates
        assert "context_ids" not in updates[0]["description"], updates[0]["description"]
        assert "contextes" in updates[0]["description"] and "poids" in updates[0]["description"]
        assert set(updates[0]["meta"]["fields"]) == {"context_ids", "weight"}


@pytest.mark.asyncio
async def test_a_key_the_server_does_not_know_is_never_printed(client):
    """/sync journals the client's payload as it came, unknown keys included."""
    async with house() as h:
        task_id = await _create_task(client, h, "Poussière")

        def sync_body(data: dict) -> dict:
            return {"device_id": str(uuid.uuid4()), "last_server_version": 0, "changes": [{
                "client_op_id": str(uuid.uuid4()), "table": "tasks", "operation": "UPDATE",
                "row_id": task_id, "data": data,
                "client_timestamp": "2031-01-01T09:00:00+00:00"}]}

        mixed = await client.post("/api/sync", headers=h.headers(0),
                                  json=sync_body({"mystery_key": 1, "weight": 5}))
        assert mixed.status_code == 200 and mixed.json()["conflicts"] == [], mixed.text
        only_unknown = await client.post("/api/sync", headers=h.headers(0),
                                         json=sync_body({"mystery_key": 2}))
        assert only_unknown.status_code == 200 and only_unknown.json()["conflicts"] == []

        updates = _of_task(await _feed(client, h), task_id, "task_updated")
        assert all("mystery_key" not in e["description"] for e in updates), updates
        assert all("mystery_key" not in e["meta"]["fields"] for e in updates), updates
        # The op that changed only an unknown key says nothing a reader could use: no line.
        assert len(updates) == 1 and "poids" in updates[0]["description"], updates


def test_every_field_the_server_writes_on_a_task_has_a_label():
    from app.api.sync import _TASK_UPDATE_FIELDS
    from app.schemas.task import TaskUpdate
    from app.services.history_feed import TASK_FIELD_LABELS_BY_LOCALE

    written = (
        set(TaskUpdate.model_fields)          # (a) REST update
        | set(_TASK_UPDATE_FIELDS)            # (b) /sync whitelist
        | {"is_active", "assigned_to_ids"}    # (c) task_departure.py
        | {"is_archived"}                     # (d) archiving.py
    )
    for locale, labels in TASK_FIELD_LABELS_BY_LOCALE.items():
        assert written <= set(labels), (locale, sorted(written - set(labels)))
    assert set(TASK_FIELD_LABELS_BY_LOCALE["fr"]) == set(TASK_FIELD_LABELS_BY_LOCALE["en"])


# --- (2) title at the time ----------------------------------------------------------------


@pytest.mark.asyncio
async def test_each_line_carries_the_title_of_its_time(client):
    async with house() as h:
        task_id = await _create_task(client, h, "Poubelles")
        # Each vote is sent twice: a replay touches `updated_at`, which keeps this test about
        # titles (the first vote alone is the subject of the next test).
        for _ in range(2):
            first = await client.put(f"/api/tasks/{task_id}/effort", headers=h.headers(0),
                                     json={"points": 3})
            assert first.status_code == 200, first.text
        await asyncio.sleep(0.05)
        renamed = await client.put(f"/api/tasks/{task_id}", headers=h.headers(0),
                                   json={"title": "Sortir les poubelles"})
        assert renamed.status_code == 200, renamed.text
        await asyncio.sleep(0.05)
        for _ in range(2):
            second = await client.put(f"/api/tasks/{task_id}/affinity", headers=h.headers(0),
                                      json={"score": 8})
            assert second.status_code == 200, second.text

        feed = await _feed(client, h)
        [created] = _of_task(feed, task_id, "task_created")
        [update] = _of_task(feed, task_id, "task_updated")
        [effort] = _of_task(feed, task_id, "effort")
        [affinity] = _of_task(feed, task_id, "affinity")
        assert "'Poubelles'" in created["description"], created["description"]
        assert created["task"]["title"] == "Poubelles"
        # The edit that renames carries the NEW title (its own row says it).
        assert "'Sortir les poubelles'" in update["description"], update["description"]
        # A vote cast before the rename keeps the old name, one cast after takes the new one.
        assert "'Poubelles'" in effort["description"], effort["description"]
        assert effort["task"]["title"] == "Poubelles"
        assert "'Sortir les poubelles'" in affinity["description"], affinity["description"]

        # The search matches what the line prints: the old name finds the old lines.
        found = await _feed(client, h, q="Poubelles")
        assert _of_task(found, task_id, "effort") and _of_task(found, task_id, "task_created")
        by_new_name = await _feed(client, h, q="Sortir")
        assert not _of_task(by_new_name, task_id, "effort")
        assert _of_task(by_new_name, task_id, "affinity")


# --- (3) first votes ----------------------------------------------------------------------


@pytest.mark.asyncio
async def test_a_first_vote_appears_once_and_a_replay_adds_no_line(client):
    async with house() as h:
        task_id = await _create_task(client, h, "Vitres")
        # Member 0 votes and the request is replayed (it touches `updated_at`, which made the
        # old filter let the vote through); member 1 votes ONCE, the case the old filter hid.
        for member, times in ((0, 2), (1, 1)):
            for path, body in (("effort", {"points": 5}), ("affinity", {"score": 6})):
                for _ in range(times):
                    r = await client.put(f"/api/tasks/{task_id}/{path}",
                                         headers=h.headers(member), json=body)
                    assert r.status_code == 200, r.text

        feed = await _feed(client, h, family="votes")
        efforts, affinities = _of_task(feed, task_id, "effort"), _of_task(feed, task_id, "affinity")
        assert sorted(e["user"] for e in efforts) == ["M0", "M1"], efforts
        assert sorted(e["user"] for e in affinities) == ["M0", "M1"], affinities
        assert all(e["meta"] == {"points": 5, "minutes": 25} for e in efforts)

        # A changed value updates the member's line: still one per member and task.
        r = await client.put(f"/api/tasks/{task_id}/effort", headers=h.headers(0),
                             json={"points": 9})
        assert r.status_code == 200
        mine = [e for e in _of_task(await _feed(client, h, family="votes"), task_id, "effort")
                if e["user"] == "M0"]
        assert len(mine) == 1 and mine[0]["meta"]["points"] == 9, mine

        # The member filter reads the same source.
        only_m1 = await _feed(client, h, actor_id=str(h.user_ids[1]), family="votes")
        assert {e["user"] for e in only_m1} == {"M1"} and len(only_m1) == 2, only_m1
