"""Completing an `uncertain` task through REST infers its interval.

From the second live completion on, `POST /tasks/{id}/complete` recomputes
`inferred_interval_days`, and that branch resolves the task's context flags
(freeze when away). It raised a NameError — a 500 to the caller — because the
flag helpers were never imported in `completions.py`; no test reached the
branch. The /sync path does not call it and was not affected.

PATHS that reach `_recompute_inferred_interval` (grep of its callers), listed
before the cases. The defect was a module-level import, so one fix covers all
of them; each path is still a separate way for the invariant "a completion of
an uncertain task never fails on the inference" to break:
  (1) POST /tasks/{id}/complete ...................................... COVERED
  (2) POST /tasks/{id}/complete-collective ........................... COVERED
  (3) DELETE of a collective completion (undo) ...... not covered here: it
      only recomputes after rows were removed, through the same function and
      module as (1) and (2); the sweep `pyflakes app/` found no other
      undefined name on 2026-10-12.
  (4) presence change (prompts.py, lazy import) ..... not covered here: it
      imports the function from this module, so it shares (1)'s fix; reaching
      it needs a household with freeze enabled and a presence answer.
"""

from __future__ import annotations

import pytest

from tests.conftest import TEST_USER_ID


async def _uncertain_task(client, headers) -> str:
    resp = await client.post(
        "/api/tasks",
        json={
            "title": "uncertain",
            "assigned_to_ids": [TEST_USER_ID],
            "recurrence_rule": {"type": "uncertain"},
        },
        headers=headers,
    )
    assert resp.status_code == 201, resp.text
    return resp.json()["id"]


@pytest.mark.asyncio
@pytest.mark.parametrize("path", ["complete", "complete-collective"])
async def test_second_rest_completion_of_uncertain_task_infers_interval(
    client, auth_headers, path,
):
    task_id = await _uncertain_task(client, auth_headers)
    for when in ("2026-03-01T09:00:00+00:00", "2026-03-11T09:00:00+00:00"):
        body = {"completed_at": when}
        if path == "complete-collective":
            body["participant_user_ids"] = [TEST_USER_ID]
        r = await client.post(f"/api/tasks/{task_id}/{path}", json=body, headers=auth_headers)
        assert r.status_code == 201, r.text

    task = (await client.get(f"/api/tasks/{task_id}", headers=auth_headers)).json()
    assert task["inferred_interval_days"] == pytest.approx(10), task
