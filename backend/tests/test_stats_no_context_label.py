"""The no-context bucket of the Stats breakdowns is named in the caller's language.

Installed APKs display `context_name` (/stats/category-frequency) and `room_name`
(/stats/long-term) as sent: a phone set to English showed « Sans contexte » until
2026-11-16 (todo « "Sans contexte" servi en français à tous par les stats »).

PATHS by which the bucket can come back in the wrong language:
  (1) /stats/category-frequency ignoring Accept-Language ..................... COVERED
  (2) /stats/long-term ignoring Accept-Language ............................. COVERED
  (3) an unsupported language falling back to something else than French ... COVERED (unit)
  non couvert : une autre route qui nommerait ce seau, il n'y en a pas
      (grep « Sans contexte » dans app/, 2026-11-16 : context_breakdown.py seul).
"""

import pytest

from app.services.context_breakdown import no_context_label
from tests.conftest import TEST_USER_ID


def test_unknown_locale_falls_back_to_french():
    assert no_context_label("de") == "Sans contexte"


async def _complete_a_task_without_context(client, auth_headers):
    created = await client.post(
        "/api/tasks", headers=auth_headers,
        json={
            "title": "Task with no context (label test)",
            "assigned_to_ids": [TEST_USER_ID],
            "recurrence_rule": {"type": "interval", "every_n_days": 3},
        },
    )
    assert created.status_code == 201, created.text
    done = await client.post(
        f"/api/tasks/{created.json()['id']}/complete", headers=auth_headers, json={},
    )
    assert done.status_code in (200, 201), done.text


@pytest.mark.asyncio
@pytest.mark.parametrize("language, expected", [("en-US", "No context"), ("fr-FR", "Sans contexte")])
async def test_both_breakdowns_name_the_bucket_in_the_callers_language(
    client, auth_headers, language, expected,
):
    await _complete_a_task_without_context(client, auth_headers)
    headers = {**auth_headers, "Accept-Language": language}

    freq = await client.get("/api/stats/category-frequency?period=all", headers=headers)
    assert freq.status_code == 200, freq.text
    buckets = [c["context_name"] for c in freq.json()["contexts"] if c["context_id"] is None]
    assert buckets == [expected], freq.json()["contexts"]

    long_term = await client.get("/api/stats/long-term?period=month", headers=headers)
    assert long_term.status_code == 200, long_term.text
    rooms = [r["room_name"] for r in long_term.json()["by_room"] if r["room_id"] is None]
    assert rooms == [expected], long_term.json()["by_room"]
