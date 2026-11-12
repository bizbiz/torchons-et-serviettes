"""GET /tasks/{id}/history names a task's creation as a creation.

Seen on 2026-11-12: a task only ever created showed « Camille a modifié 6 champs » — its single
change_log row, the INSERT, came back as `task_update` with every field of the new task.

PATHS by which the creation is shown as an edit:
  (1) the INSERT row of `tasks` mapped to `task_update` (api/tasks.py, _event_type) ...... COVERED
  (2) a later UPDATE of the same task, which must stay `task_update` ..................... COVERED
  non couvert : an app older than this change, which maps `task_create` to « a modifié la tâche »
  (UNKNOWN) — readable, not wrong; checked by reading TaskHistoryEntity.toDomain.
"""

from __future__ import annotations

import pytest

from tests.conftest import TEST_USER_ID


@pytest.mark.asyncio
async def test_creation_then_edit(client, auth_headers):
    created = await client.post("/api/tasks", headers=auth_headers, json={
        "title": "history pytest", "assigned_to_ids": [TEST_USER_ID],
        "recurrence_rule": {"type": "interval", "every_n_days": 3},
    })
    assert created.status_code == 201, created.text
    task_id = created.json()["id"]

    def task_events(resp):  # the creator's frequency vote is logged too: not the task's own row
        return [e["event_type"] for e in resp.json() if not e["event_type"].startswith("vote_")]

    first = await client.get(f"/api/tasks/{task_id}/history", headers=auth_headers)
    assert first.status_code == 200, first.text
    assert task_events(first) == ["task_create"], first.json()

    edited = await client.put(f"/api/tasks/{task_id}", headers=auth_headers, json={"title": "history pytest 2"})
    assert edited.status_code == 200, edited.text
    after = await client.get(f"/api/tasks/{task_id}/history", headers=auth_headers)
    assert task_events(after) == ["task_update", "task_create"], after.json()
