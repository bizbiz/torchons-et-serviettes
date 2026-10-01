"""The lists of recurrence types must say the same thing everywhere.

A type known by one list and not by another fails silently: /sync accepted
`calendar` for weeks while REST answered 422, and a stored type that no read
path knows leaves the task inert (no due date, green forever).

Lists that enumerate recurrence types, and what keeps each one aligned:
  - `LIVING_RULE_TYPES` (recurrence.py) ............ the reference.
  - REST `RecurrenceRule` (schemas/task.py) ......... reads LIVING_RULE_TYPES
      directly; this file proves each living type is ACCEPTED end to end.
  - `VOTABLE_RULE_TYPES` (recurrence.py) ........... must stay a subset.
  - Kotlin `RecurrenceTypes.LIVING` / `.VOTABLE` .... compared below by reading
      the Kotlin source; `RecurrenceTypesTest.kt` then proves, on the JVM, that
      the calculator gives every Kotlin living type a due date.
  - not covered: per-type `when` branches in screens (labels, editor). A
      missing branch there shows a raw type name, it does not lose a due date.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

from app.services.recurrence import LIVING_RULE_TYPES, VOTABLE_RULE_TYPES
from tests.conftest import TEST_USER_ID

#: One valid rule per living type. A new type without a sample fails
#: `test_every_living_type_has_a_sample`, which is the point.
SAMPLES = {
    "interval": {"type": "interval", "every_n_days": 7},
    # Distinct values: equal ones are collapsed into an `interval` by the
    # creator's auto-vote, and the test would then read another type back.
    "monthly_variable": {"type": "monthly_variable", "days_per_month": {str(m): 20 + m for m in range(1, 13)}},
    "uncertain": {"type": "uncertain"},
    "calendar": {"type": "calendar", "months": [5], "day": 15, "grace_days": 0},
    "anniversary": {"type": "anniversary", "every_n_months": 12},
}

KOTLIN_TYPES = (
    Path(__file__).resolve().parents[2]
    / "android/app/src/main/java/com/torchons/app/data/model/RecurrenceTypes.kt"
)


def test_every_living_type_has_a_sample():
    assert set(SAMPLES) == set(LIVING_RULE_TYPES)


def test_votable_types_are_living_types():
    assert VOTABLE_RULE_TYPES <= LIVING_RULE_TYPES


@pytest.mark.asyncio
@pytest.mark.parametrize("rtype", sorted(SAMPLES))
async def test_rest_accepts_every_living_type(client, auth_headers, rtype):
    resp = await client.post(
        "/api/tasks",
        json={"title": f"list {rtype}", "assigned_to_ids": [TEST_USER_ID],
              "recurrence_rule": SAMPLES[rtype]},
        headers=auth_headers,
    )
    assert resp.status_code == 201, resp.text
    assert resp.json()["recurrence_rule"]["type"] == rtype


def _kotlin_set(name: str) -> set[str]:
    src = KOTLIN_TYPES.read_text(encoding="utf-8")
    m = re.search(rf"\bval {name}\b[^=]*=\s*setOf\(([^)]*)\)", src)
    assert m, f"`val {name} = setOf(...)` not found in {KOTLIN_TYPES.name}"
    return set(re.findall(r'"([a-z_]+)"', m.group(1)))


@pytest.mark.skipif(not KOTLIN_TYPES.exists(), reason="Android sources not checked out")
def test_kotlin_living_types_match():
    assert _kotlin_set("LIVING") == set(LIVING_RULE_TYPES)


@pytest.mark.skipif(not KOTLIN_TYPES.exists(), reason="Android sources not checked out")
def test_kotlin_votable_types_match():
    assert _kotlin_set("VOTABLE") == set(VOTABLE_RULE_TYPES)
