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


@pytest.mark.asyncio
async def test_sync_refuses_an_oversized_batch(client, auth_headers, device_a):
    """`changes` is bounded at 2000: the app batches by 100, a hostile payload is refused."""
    import uuid
    from datetime import datetime, timezone

    def op():
        return {"client_op_id": str(uuid.uuid4()), "table": "tasks", "operation": "DELETE",
                "row_id": str(uuid.uuid4()), "data": None,
                "client_timestamp": datetime(2026, 1, 1, tzinfo=timezone.utc).isoformat()}

    ok = await client.post("/api/sync", headers=auth_headers, json={
        "device_id": device_a, "last_server_version": 0, "changes": [op() for _ in range(3)]})
    assert ok.status_code == 200, ok.text
    too_many = await client.post("/api/sync", headers=auth_headers, json={
        "device_id": device_a, "last_server_version": 0, "changes": [op() for _ in range(2001)]})
    assert too_many.status_code == 422, too_many.text[:300]


@pytest.mark.asyncio
async def test_pairing_info_takes_the_token_in_the_body(client, auth_headers):
    """POST /pairing/info: the token stays out of the URL, hence out of the access logs.

    The GET with the token in the path was removed on 2026-10-16: it must stay gone.
    """
    rate_limit.reset()
    init = await client.post("/api/pairing/init", headers=auth_headers)
    assert init.status_code == 200, init.text
    token = init.json()["invite_token"]

    by_body = await client.post("/api/pairing/info", json={"invite_token": token})
    assert by_body.status_code == 200, by_body.text
    assert by_body.json()["kind"] == "foyer_join", by_body.text

    legacy = await client.get(f"/api/pairing/info/{token}")
    assert legacy.status_code == 404, legacy.text
    rate_limit.reset()
