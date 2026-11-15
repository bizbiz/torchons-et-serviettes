"""Hardening points reviewed before the code went public (checklist closed 2026-11-10). These
tests, and the maintainer's local check script, stay as regression guards.

Paths, and what covers each:
- j. a scoped token (the 5-minute restore token) used as a session — refused 401;
- h. a task title a spreadsheet would run as a formula — exported with a leading apostrophe;
- a. a placeholder or short JWT_SECRET — the app refuses to start;
- the Play Billing stubs (/verify grants premium on any token, /rtdn unauthenticated) — 404.
Not covered by a test (read, and checked by the local script): b. CORS
without credentials, c. docs off when EXPOSE_API_DOCS=false (the flag is read at import),
d. /api/health without exception text, e. USER in the Dockerfile, f. python-jose floor,
g. dev Postgres on loopback, i. op_error detail reduced to the class for unexpected errors.
"""

from __future__ import annotations

import uuid

import pytest

from tests.conftest import DEVICE_A, TEST_USER_ID


@pytest.mark.asyncio
async def test_a_restore_token_is_not_a_session(client, auth_headers):
    from app.api.recovery import _create_restore_token

    standard = await client.get("/api/tasks", headers=auth_headers)
    assert standard.status_code == 200, standard.text
    restore = {"Authorization": f"Bearer {_create_restore_token(TEST_USER_ID)}"}
    r = await client.get("/api/tasks", headers=restore)
    assert r.status_code == 401, r.text


@pytest.mark.asyncio
async def test_billing_stubs_are_not_served(client, auth_headers):
    """The billing stubs grant premium on any token: not mounted unless BILLING_STUB_ENABLED."""
    verify = await client.post("/api/billing/verify", headers=auth_headers, json={
        "purchase_token": "anything", "product_id": "premium_monthly"})
    assert verify.status_code == 404, verify.text
    rtdn = await client.post("/api/billing/rtdn", json={"message": {"data": "x"}})
    assert rtdn.status_code == 404, rtdn.text
    health = await client.get("/api/health")
    assert health.status_code == 200, health.text


@pytest.mark.asyncio
async def test_a_formula_title_is_exported_as_text(client, auth_headers):
    title = f"=HYPERLINK(\"http://x\",\"{uuid.uuid4().hex[:6]}\")"
    task_id = str(uuid.uuid4())
    r = await client.post("/api/sync", headers=auth_headers, json={
        "device_id": DEVICE_A, "last_server_version": 0, "changes": [{
            "client_op_id": str(uuid.uuid4()), "table": "tasks", "operation": "INSERT",
            "row_id": task_id, "data": {"title": title, "assigned_to_ids": [TEST_USER_ID]},
            "client_timestamp": "2026-01-01T00:00:00+00:00",
        }]})
    assert r.status_code == 200 and len(r.json()["applied"]) == 1, r.text

    csv = await client.get("/api/export/tasks.csv", headers=auth_headers)
    assert csv.status_code == 200, csv.text[:200]
    line = next(l for l in csv.text.splitlines() if title[12:30] in l or "HYPERLINK" in l)
    assert "'=HYPERLINK" in line, line


@pytest.mark.parametrize("secret", ["CHANGE_ME", "CHANGE_ME_generate_with_secrets", "short-secret"])
def test_a_weak_jwt_secret_refuses_to_start(monkeypatch, secret):
    from app import config

    monkeypatch.setattr(config.settings, "jwt_secret", secret)
    with pytest.raises(RuntimeError):
        config._validate_required()
