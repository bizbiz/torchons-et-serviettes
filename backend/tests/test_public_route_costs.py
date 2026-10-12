"""Routes reachable without authentication have a bounded cost per client.

Paths, and what covers each:
- `POST /recovery/restore` past its per-IP allowance (5 per minute) — 429 RATE_LIMITED with a
  Retry-After, after five ordinary 404 answers (the standard, played first);
- an unbounded `limit` query parameter — 422 above the bound, 200 at the bound;
Not covered by a test: the four other limited routes (`restore/confirm`, `create-foyer`,
`pairing/info`, `pairing/join`) share the same `rate_limited` dependency; argon2 moved off the
event loop (`asyncio.to_thread`), the `absent_since` clamp and the pairing row lock are fixed by
reading — the lock needs two concurrent transactions, the event loop a timing harness.
"""

from __future__ import annotations

import pytest

from app.utils import rate_limit


@pytest.mark.asyncio
async def test_recovery_restore_is_rate_limited_per_ip(client):
    rate_limit.reset()
    statuses = []
    for _ in range(5):
        r = await client.post("/api/recovery/restore", json={"recovery_code": "ZZZZ2222"})
        statuses.append(r.status_code)
    assert statuses == [404] * 5, statuses

    r = await client.post("/api/recovery/restore", json={"recovery_code": "ZZZZ2222"})
    assert r.status_code == 429, r.text
    assert r.json()["code"] == "RATE_LIMITED", r.text
    assert int(r.headers["retry-after"]) >= 1, r.headers
    rate_limit.reset()


@pytest.mark.asyncio
async def test_completions_limit_is_bounded(client, auth_headers):
    at_bound = await client.get("/api/completions?limit=5000", headers=auth_headers)
    assert at_bound.status_code == 200, at_bound.text
    above = await client.get("/api/completions?limit=100000", headers=auth_headers)
    assert above.status_code == 422, above.text
