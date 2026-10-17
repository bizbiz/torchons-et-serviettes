"""A « done together » counts once in the inferred interval of an uncertain task.

A collective completion writes one row per participant, at the same instant, under one
`collective_id`. The recompute took every live row, so two collective completions ten days
apart gave the gaps [0, 10, 0] and a median of 5.05 instead of 10 (seen in beta on
2026-10-12, task « test rythme incertain »).

PATHS that feed the recompute its completions (one query, `_recompute_inferred_interval`):
  (1) rows sharing a collective_id ........................................ COVERED
  (2) solo rows (collective_id null) mixed with collective ones ........... COVERED
  (3) two solo rows at the same instant, no collective_id ... not covered: no writer
      produces them (REST complete and /sync INSERT write one row, and the anti
      double-tap refuses a second one within the gap); deduplicating on the instant
      would also merge two genuine completions.

The rows are written directly, as complete-collective writes them; both carry the same
`completed_by`, which the interval does not read, so no second household member is needed.
Dates are whole days before now because the second pass keeps only recent gaps relative to
now; the gaps themselves are exact multiples of a day, so the result does not depend on the
hour the test runs.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

import pytest

from tests.conftest import TEST_FOYER_ID, TEST_USER_ID


async def _interval_after(rows: list[tuple[datetime, uuid.UUID | None]]) -> float | None:
    from app.api.completions import _recompute_inferred_interval
    from app.database import async_session
    from app.models.completion import TaskCompletion
    from app.models.task import Task

    async with async_session() as session:
        task = Task(foyer_id=uuid.UUID(TEST_FOYER_ID), title="uncertain collective",
                    recurrence_rule={"type": "uncertain"})
        session.add(task)
        await session.flush()
        for when, collective_id in rows:
            session.add(TaskCompletion(task_id=task.id, completed_by=uuid.UUID(TEST_USER_ID),
                                       completed_at=when, collective_id=collective_id))
        await session.flush()
        await _recompute_inferred_interval(session, task, task.foyer_id)
        value = task.inferred_interval_days
        await session.rollback()
    return value


def _days_ago(n: int) -> datetime:
    return datetime.now(timezone.utc).replace(microsecond=0) - timedelta(days=n)


@pytest.mark.asyncio
async def test_two_collective_completions_ten_days_apart_give_ten_days():
    first, second = uuid.uuid4(), uuid.uuid4()
    rows = [(_days_ago(20), first), (_days_ago(20), first),
            (_days_ago(10), second), (_days_ago(10), second)]
    assert await _interval_after(rows) == pytest.approx(10)


@pytest.mark.asyncio
async def test_solo_and_collective_completions_mix():
    # Gaps counted per row would be [10, 0, 10, 0], median 5.05: the case must be able to fail.
    first, second = uuid.uuid4(), uuid.uuid4()
    rows = [(_days_ago(30), None), (_days_ago(20), first), (_days_ago(20), first),
            (_days_ago(10), second), (_days_ago(10), second)]
    assert await _interval_after(rows) == pytest.approx(10)
