"""A frequency vote must never overwrite a date-anchored rule.

The consensus always writes `interval` or `monthly_variable`. Before the guard,
one vote on a `calendar` task silently turned it into an interval, bringing
back the drift the calendar type exists to remove.

PATHS that write a vote (callers of `upsert_vote_and_sync_consensus`), listed
before writing the cases:
  (1) PUT /tasks/{id}/frequency ........................... COVERED
  (2) POST /tasks auto-vote at creation ..................... not covered:
      it only fires on `interval`/`monthly_variable`, by an explicit branch.
  (3) PUT /tasks/{id} switching back to a votable type ...... not covered:
      the NEW rule is votable by definition, nothing to overwrite.
  (4) POST /prompts/request-revote (kind=frequency) ......... not covered
      here: refuses by the same `is_votable`, it creates no vote itself.
  (5) a VOTE_FREQUENCY prompt still pending when the task became calendar:
      closed by `prompt_validity._task_still_votable`, answered through (1).
"""

from __future__ import annotations

import uuid

import pytest
from sqlalchemy import update

from app.database import async_session
from app.models.task import Task
from app.services.recurrence import is_votable
from tests.conftest import TEST_USER_ID

CALENDAR = {"type": "calendar", "months": [5], "day": 15, "grace_days": 0}


async def _create_task(client, headers, rule: dict) -> str:
    """Create through REST as an interval, then write `rule` in base.

    REST `RecurrenceRule` still refuses `calendar` (the app creates through
    /sync, which normalises with `normalize_recurrence_rule`), so the rule
    under test is written directly.
    """
    resp = await client.post(
        "/api/tasks",
        json={
            "title": "vote guard",
            "assigned_to_ids": [TEST_USER_ID],
            "recurrence_rule": {"type": "interval", "every_n_days": 7},
        },
        headers=headers,
    )
    assert resp.status_code in (200, 201), resp.text
    task_id = resp.json()["id"]
    async with async_session() as session:
        await session.execute(
            update(Task).where(Task.id == uuid.UUID(task_id)).values(recurrence_rule=rule)
        )
        await session.commit()
    return task_id


@pytest.mark.parametrize(
    "rule, attendu",
    [
        ({"type": "interval", "every_n_days": 7}, True),
        ({"type": "monthly_variable", "days_per_month": {"1": 30}}, True),
        (CALENDAR, False),
        ({"type": "uncertain"}, False),
        # A type that does not exist yet (the anniversary form) is refused by
        # construction: the set is an allowlist.
        ({"type": "anniversary"}, False),
        (None, False),
    ],
)
def test_only_interval_rules_are_votable(rule, attendu):
    assert is_votable(rule) is attendu


@pytest.mark.asyncio
async def test_vote_on_interval_task_is_accepted(client, auth_headers):
    """Positive control: same request, votable rule -> the vote goes through."""
    task_id = await _create_task(client, auth_headers, {"type": "interval", "every_n_days": 7})
    resp = await client.put(
        f"/api/tasks/{task_id}/frequency", json={"every_n_days": 10}, headers=auth_headers
    )
    assert resp.status_code == 200, resp.text


@pytest.mark.asyncio
async def test_vote_on_calendar_task_is_refused_and_rule_kept(client, auth_headers):
    task_id = await _create_task(client, auth_headers, CALENDAR)
    resp = await client.put(
        f"/api/tasks/{task_id}/frequency", json={"every_n_days": 10}, headers=auth_headers
    )
    assert resp.status_code == 409, resp.text
    assert resp.json().get("code") == "GAMIF_RULE_NOT_VOTABLE", resp.text

    task = (await client.get(f"/api/tasks/{task_id}", headers=auth_headers)).json()
    assert task["recurrence_rule"]["type"] == "calendar"
