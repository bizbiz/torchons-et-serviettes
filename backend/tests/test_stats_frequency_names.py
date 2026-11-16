"""GET /api/stats/category-frequency serves its list under the current names only.

The app reads `contexts` / `context_id` / `context_name` since 2026-11-12 (v0.44.3, versionCode
112). The old names (`categories` / `category_id` / `category_name`) were retired on 2026-11-16,
once the version gate reached 112 (docs/version-gate.fr.md).

PATHS by which « only the current names are served » can break:
  (1) the model declaring an old name again ................................ COVERED (unit)
  (2) the route serializing through its response_model ..................... COVERED
  (3) another route building CategoryFrequencyResponse ..... not covered: there is none
      (grep CategoryFrequencyResponse, 2026-11-16: stats.py only).
Negative witness, 2026-11-16: both tests fail on the previous schema (`categories` present).
"""

import uuid

import pytest

from app.schemas.stats_frequency import (
    CategoryFrequencyResponse,
    CategoryFrequencyUser,
    ContextFrequencyEntry,
)

OLD_NAMES = {"categories", "category_id", "category_name"}


def test_the_serialized_body_carries_no_old_name():
    cid = uuid.uuid4()
    body = CategoryFrequencyResponse(
        users=[CategoryFrequencyUser(user_id=uuid.uuid4(), display_name="A")],
        contexts=[
            ContextFrequencyEntry(context_id=cid, context_name="Cuisine", counts=[3], total=3),
            ContextFrequencyEntry(context_id=None, context_name="Sans contexte", counts=[1], total=1),
        ],
    ).model_dump(mode="json")
    assert set(body) == {"users", "contexts"}, list(body)
    assert body["contexts"] == [
        {"context_id": str(cid), "context_name": "Cuisine", "counts": [3], "total": 3},
        {"context_id": None, "context_name": "Sans contexte", "counts": [1], "total": 1},
    ]


@pytest.mark.asyncio
async def test_the_route_serves_the_current_names_only(client, auth_headers):
    resp = await client.get("/api/stats/category-frequency?period=all", headers=auth_headers)
    assert resp.status_code == 200, resp.text
    data = resp.json()
    assert "contexts" in data and not OLD_NAMES & set(data), list(data)
    for entry in data["contexts"]:
        assert not OLD_NAMES & set(entry), list(entry)
