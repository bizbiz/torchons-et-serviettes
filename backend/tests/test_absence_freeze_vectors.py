"""The freeze rule says the same thing on the server and in the app.

The app keeps its own copy (`AbsenceFreeze.kt`) because the « Pendant une
absence » sheet must reclassify offline. Both sides are checked against one
table, `shared/test-vectors/absence_freeze.json`; `AbsenceFreezeTest.kt` reads
the same file on the JVM.

Not covered: the database read in `resolve_flags_for_tasks` (which contexts
are attached, soft-deleted ones skipped); this file checks the rule only.
"""

from __future__ import annotations

import itertools
import json
from pathlib import Path

import pytest

from app.services.task_flags import combine_flags

VECTORS = (
    Path(__file__).resolve().parents[2] / "shared/test-vectors/absence_freeze.json"
)
CASES = json.loads(VECTORS.read_text(encoding="utf-8"))["cases"]


def _key(contexts: list[dict]) -> tuple:
    return tuple(sorted((c["presence"], c["accrues"]) for c in contexts))


MAX_CONTEXTS = 5


def test_table_is_exhaustive_up_to_five_contexts():
    """A case dropped from the table would silently stop being checked on both sides.

    Two contexts would be enough for a pure OR; going to five also catches a rule that
    counts (« at least two… ») or reads only the first few contexts.
    """
    kinds = list(itertools.product([False, True], repeat=2))
    expected = {()}
    for size in range(1, MAX_CONTEXTS + 1):
        expected |= {
            tuple(sorted(combo))
            for combo in itertools.combinations_with_replacement(kinds, size)
        }
    found = [_key(c["contexts"]) for c in CASES]
    assert len(found) == len(set(found)), "duplicate case in the table"
    assert set(found) == expected


@pytest.mark.parametrize("case", CASES, ids=lambda c: str(_key(c["contexts"])))
def test_server_rule_matches_table(case):
    flags = combine_flags((c["presence"], c["accrues"]) for c in case["contexts"])
    assert flags.freezes_when_away is case["freezes"]
