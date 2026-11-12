"""GET /api/stats/category-frequency serves its list under the old AND the current names.

The app reads `contexts` / `context_id` / `context_name` since 2026-11-12; an APK from before
reads `categories` / `category_id` / `category_name`. Both must carry the same list until the old
names are retired (todo : « Renommer les champs d'API qui parlent encore de pièces »).

PATHS by which « both names carry the same list » can break:
  (1) the model: `contexts` computed from `categories` .................... COVERED (unit)
  (2) the route serializing it through its response_model ................. COVERED (red on
      HEAD 2026-11-12: the body had only `users` and `categories`)
  (3) another route building CategoryFrequencyResponse ..... not covered: there is none
      (grep CategoryFrequencyResponse, 2026-11-12: stats.py only).
"""

import uuid

import pytest

from app.schemas.stats_frequency import (
    CategoryFrequencyEntry,
    CategoryFrequencyResponse,
    CategoryFrequencyUser,
)


def test_contexts_mirror_categories_in_the_serialized_body():
    cid = uuid.uuid4()
    body = CategoryFrequencyResponse(
        users=[CategoryFrequencyUser(user_id=uuid.uuid4(), display_name="A")],
        categories=[
            CategoryFrequencyEntry(category_id=cid, category_name="Cuisine", counts=[3], total=3),
            CategoryFrequencyEntry(category_id=None, category_name="Sans contexte", counts=[1], total=1),
        ],
    ).model_dump(mode="json")
    assert body["contexts"] == [
        {"context_id": str(cid), "context_name": "Cuisine", "counts": [3], "total": 3},
        {"context_id": None, "context_name": "Sans contexte", "counts": [1], "total": 1},
    ]


@pytest.mark.asyncio
async def test_the_route_serves_both_names(client, auth_headers):
    resp = await client.get("/api/stats/category-frequency?period=all", headers=auth_headers)
    assert resp.status_code == 200, resp.text
    data = resp.json()
    assert "contexts" in data and "categories" in data, list(data)
    assert [(c["context_id"], c["context_name"], c["total"]) for c in data["contexts"]] == [
        (c["category_id"], c["category_name"], c["total"]) for c in data["categories"]
    ]
