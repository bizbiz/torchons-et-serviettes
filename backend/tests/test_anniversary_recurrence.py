"""Recurrence `anniversary` — anchored on the FIRST completion, never drifts.

"One year after the first time" (vaccines, servicing). `interval: 365` drifts:
done three weeks late, the next one is three weeks later too. `calendar` does
not drift but needs a month and a day known in advance. `anniversary` takes its
anchor from the household's own history: the earliest live completion.

Occurrences are `anchor + k * every_n_months` (k >= 1), each computed from the
anchor (never iterated, so a 31st does not decay to the 28th). The due one is
the earliest occurrence NOT honoured by the last completion, an occurrence
being honoured by a completion made on or after its orange window opens.

Dates are frozen in every case: none of these computations reads the clock.

PATHS by which the invariant "an anniversary task is due one period after its
anchor, whatever the lateness" can be broken, listed before the cases:
  (1) the pure computation (`next_anniversary_due`, `compute_due_date`) . COVERED
  (2) a caller passing no first completion ............................. COVERED
      (degrades to last + period, never to "no due date")
  (3) REST POST /tasks refusing or mis-storing the rule ................ COVERED
  (4) /sync INSERT refusing it (LIVING_RULE_TYPES) ..................... COVERED
  (5) the frequency vote overwriting it with an interval ............... COVERED
  (6) bonus/malus judging lateness without the anchor .................. COVERED
  (7) TaskResponse.first_completed_at after a deletion ................. COVERED
  (8) suggestions / retard / retard history callers .... not covered here:
      they pass `first_completed` from `first_completions_by_task`, the same
      helper (7) exercises through the response; an end-to-end test of each
      endpoint would need a clock frozen inside the API.
  (9) the Android mirror (TaskUrgencyCalculator.kt) .... not covered here:
      JVM unit test AnniversaryDueTest.kt, same cases.
"""

from __future__ import annotations

import uuid
from datetime import date, datetime, timedelta, timezone

import pytest

from app.models.enums import UrgencyState
from app.services.bonus_malus import compute_multiplier
from app.services.recurrence import (
    LIVING_RULE_TYPES,
    VOTABLE_RULE_TYPES,
    compute_due_date,
    compute_nominal_interval_days,
    compute_urgency_state,
    is_votable,
    min_completion_gap_seconds,
    next_anniversary_due,
    normalize_recurrence_rule,
    orange_window_cap_of,
)
from tests.conftest import TEST_USER_ID

YEARLY = {"type": "anniversary", "every_n_months": 12}


def _at(y: int, m: int, d: int) -> datetime:
    return datetime(y, m, d, 9, 0, tzinfo=timezone.utc)


# --- Normalisation ---


def test_normalises_period_to_int_with_a_yearly_default():
    assert normalize_recurrence_rule({"type": "anniversary", "every_n_months": "24"}) == {
        "type": "anniversary", "every_n_months": 24,
    }
    assert normalize_recurrence_rule({"type": "anniversary"}) == YEARLY
    assert normalize_recurrence_rule({"type": "anniversary", "every_n_months": 0}) == {
        "type": "anniversary", "every_n_months": 1,
    }


def test_anniversary_is_a_living_type_and_not_votable():
    assert "anniversary" in LIVING_RULE_TYPES
    assert "anniversary" not in VOTABLE_RULE_TYPES
    assert is_votable(YEARLY) is False


# --- Due date: the anchor decides, the lateness does not ---


def test_after_the_first_completion_the_next_one_is_a_period_later():
    assert next_anniversary_due(YEARLY, date(2025, 3, 14), date(2025, 3, 14)) == date(2026, 3, 14)


def test_done_late_does_not_drift():
    """The defect `interval: 365` has: three weeks late, three weeks later."""
    due = next_anniversary_due(YEARLY, date(2025, 3, 14), date(2026, 4, 2))
    assert due == date(2027, 3, 14), "the due date drifted with the late completion"


def test_done_inside_the_orange_window_honours_the_occurrence():
    """Done on the 10th for the 14th (7-day window): the 14th is honoured."""
    assert next_anniversary_due(YEARLY, date(2025, 3, 14), date(2026, 3, 10)) == date(2027, 3, 14)


def test_done_long_before_the_window_does_not_honour_it():
    """Two months early is not the yearly servicing: the 14th stays due."""
    assert next_anniversary_due(YEARLY, date(2025, 3, 14), date(2026, 1, 10)) == date(2026, 3, 14)


def test_a_skipped_year_stays_due_until_done():
    """Nothing since the anchor: the first unhonoured occurrence is the due one,
    however long ago it was — the task is red, it does not jump ahead."""
    assert next_anniversary_due(YEARLY, date(2023, 3, 14), date(2023, 3, 14)) == date(2024, 3, 14)


def test_multi_year_period():
    rule = {"type": "anniversary", "every_n_months": 24}
    assert next_anniversary_due(rule, date(2025, 3, 14), date(2027, 3, 20)) == date(2029, 3, 14)


def test_month_end_anchor_is_computed_from_the_anchor_not_iterated():
    """31 Jan monthly: 29 Feb, then 31 March — not 29 March by decay."""
    rule = {"type": "anniversary", "every_n_months": 1}
    assert next_anniversary_due(rule, date(2024, 1, 31), date(2024, 1, 31)) == date(2024, 2, 29)
    assert next_anniversary_due(rule, date(2024, 1, 31), date(2024, 2, 29)) == date(2024, 3, 31)


def test_compute_due_date_uses_the_first_completion_as_anchor():
    due = compute_due_date(
        YEARLY, _at(2026, 4, 2), _at(2025, 1, 1), first_completed=_at(2025, 3, 14),
    )
    assert due == date(2027, 3, 14)


def test_never_done_is_due_at_creation_like_every_other_type():
    assert compute_due_date(YEARLY, None, _at(2026, 9, 1)) == date(2026, 9, 1)


def test_without_a_known_first_the_last_is_the_anchor():
    """A caller that cannot know the first completion degrades to an interval
    of one period, never to 'no due date'."""
    assert compute_due_date(YEARLY, _at(2026, 4, 2), None) == date(2027, 4, 2)


def test_deleting_the_first_completion_moves_the_anchor_to_the_next_live_one():
    """'First' means the earliest LIVE completion: the callers read it with
    `deleted_at IS NULL`, so a deleted first hands over to the next one."""
    with_first = compute_due_date(
        YEARLY, _at(2026, 4, 2), None, first_completed=_at(2025, 3, 14),
    )
    after_delete = compute_due_date(
        YEARLY, _at(2026, 4, 2), None, first_completed=_at(2026, 4, 2),
    )
    assert with_first == date(2027, 3, 14)
    assert after_delete == date(2027, 4, 2)


# --- Orange window: 10 %, capped at 7 days, like calendar ---


@pytest.mark.parametrize("months, nominal", [(12, 365), (24, 730), (6, 182), (1, 30)])
def test_nominal_is_the_period_in_days(months, nominal):
    assert compute_nominal_interval_days({"type": "anniversary", "every_n_months": months}) == nominal


def test_orange_window_is_capped_at_seven_days():
    cap = orange_window_cap_of(YEARLY)
    nominal = compute_nominal_interval_days(YEARLY)
    due = date(2026, 3, 14)
    assert cap == 7
    assert compute_urgency_state(due, due - timedelta(days=7), nominal, 0, cap) == UrgencyState.ORANGE
    assert compute_urgency_state(due, due - timedelta(days=8), nominal, 0, cap) == UrgencyState.GREEN
    assert compute_urgency_state(due, due + timedelta(days=1), nominal, 0, cap) == UrgencyState.RED


def test_a_long_period_blocks_same_day_double_completion():
    assert min_completion_gap_seconds(YEARLY) == 86400


def test_bonus_malus_judges_lateness_against_the_anchored_due_date():
    """Done 2027-03-24 with first 2025-03-14 and last 2026-04-02: due was
    2027-03-14, so 10 days late. Without the anchor it would read as early."""
    from app.models.enums import TaskTolerance

    class _Cfg:
        bonus_pct = 0.5
        seuil_bonus_strict = seuil_bonus_normal = seuil_bonus_souple = 0.0
        seuil_malus_strict = seuil_malus_normal = seuil_malus_souple = 0.5
        plancher_malus = 0.5

    res = compute_multiplier(
        recurrence_rule=YEARLY,
        tolerance=TaskTolerance.NORMAL,
        last_completed_before_this=_at(2026, 4, 2),
        first_completed_before_this=_at(2025, 3, 14),
        completion_count_before_this=2,
        completed_at=_at(2027, 3, 24),
        config=_Cfg(),
    )
    assert res.days_late == 10, res


# --- REST and /sync accept it ---


async def _post_task(client, headers, rule):
    return await client.post(
        "/api/tasks",
        json={"title": "anniversary", "assigned_to_ids": [TEST_USER_ID], "recurrence_rule": rule},
        headers=headers,
    )


@pytest.mark.asyncio
async def test_rest_creates_an_anniversary_task(client, auth_headers):
    resp = await _post_task(client, auth_headers, {"type": "anniversary", "every_n_months": 24})
    assert resp.status_code == 201, resp.text
    assert resp.json()["recurrence_rule"] == {"type": "anniversary", "every_n_months": 24}


@pytest.mark.asyncio
async def test_rest_creates_a_calendar_task_normalised(client, auth_headers):
    """Was a 422 until now: REST only knew three types."""
    resp = await _post_task(
        client, auth_headers, {"type": "calendar", "months": [10, 5, 5], "day": 31},
    )
    assert resp.status_code == 201, resp.text
    assert resp.json()["recurrence_rule"] == {
        "type": "calendar", "months": [5, 10], "day": 28, "grace_days": 0,
    }


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "rule",
    [
        {"type": "anniversary"},
        {"type": "calendar"},
        {"type": "calendar", "months": []},
        {"type": "foo"},
    ],
)
async def test_rest_refuses_incomplete_or_unknown_rules(client, auth_headers, rule):
    resp = await _post_task(client, auth_headers, rule)
    assert resp.status_code == 422, resp.text


@pytest.mark.asyncio
async def test_rest_stores_no_null_keys(client, auth_headers):
    """Stored rules are compared for equality by the vote lock (PUT /tasks)
    against the consensus shape, which has no null keys: the schema's new
    optional fields must not leak into what gets stored."""
    resp = await _post_task(client, auth_headers, {"type": "uncertain"})
    assert resp.status_code == 201, resp.text
    assert resp.json()["recurrence_rule"] == {"type": "uncertain"}


@pytest.mark.asyncio
async def test_vote_on_anniversary_task_is_refused(client, auth_headers):
    resp = await _post_task(client, auth_headers, YEARLY)
    task_id = resp.json()["id"]
    resp = await client.put(
        f"/api/tasks/{task_id}/frequency", json={"every_n_days": 10}, headers=auth_headers
    )
    assert resp.status_code == 409, resp.text
    assert resp.json().get("code") == "GAMIF_RULE_NOT_VOTABLE", resp.text


@pytest.mark.asyncio
async def test_sync_insert_stores_an_anniversary(client, auth_headers, device_a):
    task_id = str(uuid.uuid4())
    resp = await client.post(
        "/api/sync",
        headers=auth_headers,
        json={"device_id": device_a, "last_server_version": 0, "changes": [{
            "client_op_id": str(uuid.uuid4()), "table": "tasks", "row_id": task_id,
            "operation": "INSERT", "client_timestamp": "2026-01-01T00:00:00+00:00",
            "data": {"title": "sync anniversary", "assigned_to_ids": [],
                     "recurrence_rule": {"type": "anniversary", "every_n_months": 12.0}},
        }]},
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["conflicts"] == [], resp.text
    task = (await client.get(f"/api/tasks/{task_id}", headers=auth_headers)).json()
    assert task["recurrence_rule"] == YEARLY


@pytest.mark.asyncio
async def test_first_completed_at_follows_deletion(client, auth_headers):
    """The response carries the anchor; deleting the first hands it over."""
    task_id = (await _post_task(client, auth_headers, YEARLY)).json()["id"]
    ids = []
    for when in ("2025-03-14T09:00:00+00:00", "2026-04-02T09:00:00+00:00"):
        r = await client.post(
            f"/api/tasks/{task_id}/complete", json={"completed_at": when}, headers=auth_headers,
        )
        assert r.status_code == 201, r.text
        ids.append(r.json()["id"])

    task = (await client.get(f"/api/tasks/{task_id}", headers=auth_headers)).json()
    assert task["first_completed_at"].startswith("2025-03-14"), task
    assert task["last_completed_at"].startswith("2026-04-02"), task

    r = await client.delete(f"/api/tasks/{task_id}/completions/{ids[0]}", headers=auth_headers)
    assert r.status_code == 200, r.text
    task = (await client.get(f"/api/tasks/{task_id}", headers=auth_headers)).json()
    assert task["first_completed_at"].startswith("2026-04-02"), task
