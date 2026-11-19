"""Renewal delay of affinity and effort ratings: 24 months by default.

GET /api/pending-affinity asks again for a rating older than `expiry_months`. The default
passed from 6 to 24 months (the slider goes from 1 to 24). Each case builds its own foyer.

PATHS by which "the default is 24 months" can be violated (grep of `expiry_months` in app/):
  (1) Query(default=...) of GET /api/pending-affinity ........ COVERED (cases 1 and 2)
  (2) UserSettings.affinity_expiry_months, model default ..... COVERED (case 3, lazy row)
  (3) the column server_default, and rows at 6 before the migration c2d3e4f5a6b7: not
      covered here (a test never sees the pre-migration rows); the migration was run on
      the development database and its UPDATE is one statement, see its docstring.
  (4) the app (TokenManager default, hard-coded 6): not covered, Kotlin side.
The ratings are 400 and 800 days old against a 720 day cutoff: months of margin, so the
cases do not depend on the hour they run at.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from tests.module_helpers import add_completions, house


async def _rate(h, days_ago: int) -> None:
    """Make the task recurring and eligible, with ratings `days_ago` days old."""
    from app.database import async_session
    from app.models.task import Task
    from app.models.task_affinity import TaskAffinity
    from app.models.task_assignment import TaskAssignment
    from app.models.task_effort import TaskEffort

    when = datetime.now(timezone.utc) - timedelta(days=days_ago)
    user_id = h.user_ids[0]
    async with async_session() as session:
        task = await session.get(Task, h.task_id)
        task.recurrence_rule = {"type": "interval", "every_n_days": 7}
        session.add(TaskAssignment(task_id=h.task_id, user_id=user_id))
        session.add(TaskAffinity(task_id=h.task_id, user_id=user_id, score=7, updated_at=when))
        session.add(TaskEffort(task_id=h.task_id, user_id=user_id, points=3, updated_at=when))
        await session.commit()
    # Two completions, the last one more than a day old: the task may be rated.
    await add_completions(h, 2, oldest_days_ago=3)


@pytest.mark.asyncio
async def test_pending_affinity_defaults_to_24_months(client):
    async with house() as h:
        await _rate(h, days_ago=400)  # about 13 months: fresh at 24, stale at 6

        default = await client.get("/api/pending-affinity", headers=h.headers(0))
        assert default.status_code == 200, default.text
        assert default.json() == []

        six = await client.get(
            "/api/pending-affinity", params={"expiry_months": 6}, headers=h.headers(0)
        )
        assert six.status_code == 200, six.text
        assert [row["task_id"] for row in six.json()] == [str(h.task_id)]


@pytest.mark.asyncio
async def test_pending_affinity_asks_again_after_24_months(client):
    async with house() as h:
        await _rate(h, days_ago=800)  # about 26 months: stale even at the default

        default = await client.get("/api/pending-affinity", headers=h.headers(0))
        assert default.status_code == 200, default.text
        assert [row["task_id"] for row in default.json()] == [str(h.task_id)]


@pytest.mark.asyncio
async def test_new_settings_row_defaults_to_24_months(client):
    async with house() as h:
        response = await client.get("/api/settings", headers=h.headers(0))
        assert response.status_code == 200, response.text
        assert response.json()["affinity_expiry_months"] == 24
