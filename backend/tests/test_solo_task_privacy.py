"""A task assigned to one member only must not be readable by the other members of the household.

Asked on 2026-10-18: a hygiene task Bob created for herself must never show up for Quentin,
in the app or on the widget. Member B creates a task assigned to B alone and completes it; member
A then sweeps every GET route of the app, enumerated from `app.routes` so that a route added
later is swept too (through the OpenAPI schema), plus the /sync pull.

PATHS by which A can learn of B's solo task, listed before the cases:
  (1) GET routes without path parameters (lists, stats, history, export, suggestions) ... COVERED
  (2) GET routes by id, given B's task id, completion id or user id ..................... COVERED
  (3) the /sync pull from version 0 ...................................................... COVERED
  (4) /sync writes by id (UPDATE/DELETE of the task, INSERT/UPDATE/DELETE of a completion),
      given B's ids ........................................................................ COVERED
      (the REST write routes go through `visible_tasks`; not swept.)
  (5) the push notification of a REST completion (services/push.py), which carries the title:
      routed to the members who may see the task ......................................... COVERED
      (the /sync completion path sends no push.)
  (6) GET routes that require a query parameter answer 422 here, and are listed by
      `test_sweep_reached_the_routes` so that a silent 422 is not read as "no leak". Of these,
      /activity-days (B's days of work) is COVERED by its own case; /presence carries no task.
  (7) a history page cut on hidden events, read by the app as the end of the history ... COVERED

The task title carries a random suffix, so a match can only come from this task.
"""

from __future__ import annotations

import random
import uuid
from datetime import datetime, timedelta, timezone

import pytest
import pytest_asyncio
from sqlalchemy import select

from app.auth import create_token
from app.main import app

HOUSE_ID = "5010aaaa-0000-4000-8000-00000000f0c1"
A_ID = "5010aaaa-0000-4000-8000-0000000000a1"
B_ID = "5010aaaa-0000-4000-8000-0000000000b1"
A_DEVICE = str(uuid.uuid4())
B_DEVICE = str(uuid.uuid4())
FIXED_TS = "2026-10-18T08:00:00+00:00"

SOLO_TITLE = f"Soin perso de B {uuid.uuid4().hex[:8]}"
SOLO_TASK_ID = str(uuid.uuid4())
SOLO_COMPLETION_ID = str(uuid.uuid4())


def _headers(user_id, device):
    return {"Authorization": f"Bearer {create_token(user_id, device, foyer_id=HOUSE_ID)}"}


def _op(table, operation, row_id, data):
    return {
        "client_op_id": str(uuid.uuid4()), "table": table, "operation": operation,
        "row_id": row_id, "data": data, "client_timestamp": FIXED_TS,
    }


@pytest_asyncio.fixture(scope="module", autouse=True)
async def seed_household(client):
    """A and B in their own household; B owns a completed solo task."""
    from app.database import async_session
    from app.models.foyer import Foyer
    from app.models.user import User
    from app.models.user_foyer import UserFoyer

    async with async_session() as session:
        if await session.get(Foyer, uuid.UUID(HOUSE_ID)) is None:
            session.add(Foyer(id=uuid.UUID(HOUSE_ID), name="Solo privacy household"))
            await session.flush()
        for uid, name in ((A_ID, "privacy-a"), (B_ID, "privacy-b")):
            if await session.get(User, uuid.UUID(uid)) is None:
                session.add(User(
                    id=uuid.UUID(uid), username=name, display_name=name,
                    last_active_foyer_id=uuid.UUID(HOUSE_ID),
                ))
                await session.flush()
            found = await session.execute(select(UserFoyer).where(
                UserFoyer.user_id == uuid.UUID(uid), UserFoyer.foyer_id == uuid.UUID(HOUSE_ID),
            ))
            if found.scalar_one_or_none() is None:
                session.add(UserFoyer(user_id=uuid.UUID(uid), foyer_id=uuid.UUID(HOUSE_ID), role="admin"))
        await session.commit()

    body = {"device_id": B_DEVICE, "last_server_version": 0, "changes": [
        _op("tasks", "INSERT", SOLO_TASK_ID, {"title": SOLO_TITLE, "assigned_to_ids": [B_ID]}),
        _op("task_completions", "INSERT", SOLO_COMPLETION_ID, {"task_id": SOLO_TASK_ID, "completed_by": B_ID}),
    ]}
    r = await client.post("/api/sync", json=body, headers=_headers(B_ID, B_DEVICE))
    assert r.status_code == 200, r.text
    assert len(r.json()["applied"]) == 2, r.text
    yield


PARAMS = {
    "task_id": SOLO_TASK_ID,
    "completion_id": SOLO_COMPLETION_ID,
    "user_id": B_ID,
}


def _get_routes():
    # From the OpenAPI schema, not app.routes: this FastAPI nests included routers
    # (`_IncludedRouter`), and app.routes lists only /api/health at top level.
    for path, methods in app.openapi()["paths"].items():
        if "get" in methods and path.startswith("/api"):
            yield path


def _fill(path):
    """The path with B's ids in its parameters, or None if a parameter is unknown."""
    filled = path
    for name in route_params(path):
        if name not in PARAMS:
            return None
        filled = filled.replace("{" + name + "}", PARAMS[name])
    return filled


def route_params(path):
    return [part[1:-1] for part in path.split("/") if part.startswith("{") and part.endswith("}")]


async def _sweep(client):
    """(path, status, leaked) for every GET route A can call."""
    headers = _headers(A_ID, A_DEVICE)
    results = []
    for path in sorted(set(_get_routes())):
        url = _fill(path)
        if url is None:
            results.append((path, None, False))
            continue
        r = await client.get(url, headers=headers)
        leaked = r.status_code < 400 and (SOLO_TITLE in r.text or SOLO_TASK_ID in r.text)
        results.append((path, r.status_code, leaked))
    return results


@pytest.mark.asyncio
async def test_no_get_route_shows_a_solo_task_to_another_member(client):
    leaks = [(p, s) for p, s, leaked in await _sweep(client) if leaked]
    assert leaks == [], "routes that show B's solo task to A:\n" + "\n".join(f"  {s} {p}" for p, s in leaks)


@pytest.mark.asyncio
async def test_sync_pull_does_not_carry_a_solo_task_to_another_member(client):
    body = {"device_id": A_DEVICE, "last_server_version": 0, "changes": []}
    r = await client.post("/api/sync", json=body, headers=_headers(A_ID, A_DEVICE))
    assert r.status_code == 200, r.text
    assert SOLO_TITLE not in r.text and SOLO_TASK_ID not in r.text


async def _push_logs_after_b_completes(client, monkeypatch, assignees):
    """Push log events when B completes, over REST, a new task assigned to `assignees`."""
    import firebase_admin
    from structlog.testing import capture_logs

    monkeypatch.setattr(firebase_admin, "_apps", {"[DEFAULT]": object()})
    task_id = str(uuid.uuid4())
    body = {"device_id": B_DEVICE, "last_server_version": 0, "changes": [
        _op("tasks", "INSERT", task_id, {"title": f"Push {task_id[:8]}", "assigned_to_ids": assignees}),
    ]}
    r = await client.post("/api/sync", json=body, headers=_headers(B_ID, B_DEVICE))
    assert len(r.json()["applied"]) == 1, r.text
    with capture_logs() as logs:
        r = await client.post(f"/api/tasks/{task_id}/complete", json={}, headers=_headers(B_ID, B_DEVICE))
    assert r.status_code < 300, r.text
    return [e for e in logs if e.get("event", "").startswith("push_")]


@pytest.mark.asyncio
async def test_a_solo_completion_notifies_no_other_member(client, monkeypatch):
    # A is the only other member: an empty candidate list returns before any log, while a
    # candidate always leaves a push_skipped (muted, no token) or a push_sent.
    assert await _push_logs_after_b_completes(client, monkeypatch, [B_ID]) == []
    shared = await _push_logs_after_b_completes(client, monkeypatch, [A_ID, B_ID])
    assert shared != [], "control: a shared task must reach A's push routing"


@pytest.mark.asyncio
async def test_sync_writes_by_id_refuse_a_solo_task_of_another_member(client):
    """Path (4): A knows B's ids, and every /sync write on them is refused."""
    ops = [
        _op("tasks", "UPDATE", SOLO_TASK_ID, {"title": "renamed by A"}),
        _op("task_completions", "INSERT", str(uuid.uuid4()), {"task_id": SOLO_TASK_ID, "completed_by": A_ID}),
        _op("task_completions", "UPDATE", SOLO_COMPLETION_ID, {"notes": "edited by A"}),
        _op("task_completions", "DELETE", SOLO_COMPLETION_ID, {}),
        _op("tasks", "DELETE", SOLO_TASK_ID, {}),
    ]
    body = {"device_id": A_DEVICE, "last_server_version": 0, "changes": ops}
    r = await client.post("/api/sync", json=body, headers=_headers(A_ID, A_DEVICE))
    assert r.status_code == 200, r.text
    # UPDATE/DELETE of a row the caller cannot see is a no-op or a conflict, never a write.
    r = await client.get(f"/api/tasks/{SOLO_TASK_ID}", headers=_headers(B_ID, B_DEVICE))
    assert r.status_code == 200 and SOLO_TITLE in r.text, r.text
    r = await client.get(f"/api/completions/{SOLO_COMPLETION_ID}/history", headers=_headers(B_ID, B_DEVICE))
    assert "edited by A" not in r.text, r.text
    r = await client.get("/api/completions", headers=_headers(B_ID, B_DEVICE))
    mine = [c for c in r.json() if c["task_id"] == SOLO_TASK_ID]
    assert [c["completed_by"] for c in mine] == [B_ID], mine


async def _b_completes(client, assignees, completed_at):
    """B creates a task assigned to `assignees` and completes it at `completed_at`."""
    task_id = str(uuid.uuid4())
    body = {"device_id": B_DEVICE, "last_server_version": 0, "changes": [
        _op("tasks", "INSERT", task_id, {"title": f"Dated {task_id[:8]}", "assigned_to_ids": assignees}),
        _op("task_completions", "INSERT", str(uuid.uuid4()),
            {"task_id": task_id, "completed_by": B_ID, "completed_at": completed_at}),
    ]}
    r = await client.post("/api/sync", json=body, headers=_headers(B_ID, B_DEVICE))
    assert len(r.json()["applied"]) == 2, r.text
    return task_id


@pytest.mark.asyncio
async def test_activity_days_do_not_show_a_day_of_solo_work(client):
    await _b_completes(client, [B_ID], "2025-06-15T10:00:00+00:00")
    url = f"/api/activity-days?user_id={B_ID}&from=2025-06-01&to=2025-06-30"
    assert (await client.get(url, headers=_headers(A_ID, A_DEVICE))).json()["days"] == []
    # Control: B sees the day.
    assert (await client.get(url, headers=_headers(B_ID, B_DEVICE))).json()["days"] == ["2025-06-15"]


@pytest.mark.asyncio
async def test_a_history_page_is_refilled_past_hidden_events(client):
    """A page cut on hidden events used to come back empty, and the app read it as the end."""
    # The dev database keeps earlier runs: a random instant keeps their events out of this window.
    base = datetime(2000, 1, 1, tzinfo=timezone.utc) + timedelta(minutes=random.randrange(10_000_000))
    shared = await _b_completes(client, [A_ID, B_ID], base.isoformat())
    solo = {await _b_completes(client, [B_ID], (base + timedelta(hours=h)).isoformat()) for h in (1, 2, 3)}
    before = (base + timedelta(days=1)).isoformat().replace("+00:00", "Z")
    url = f"/api/history?family=completions&limit=3&before={before}"
    r = await client.get(url, headers=_headers(A_ID, A_DEVICE))
    assert r.status_code == 200, r.text
    ids = [e["task"]["id"] for e in r.json()]
    assert shared in ids and not solo & set(ids), r.json()


@pytest.mark.asyncio
async def test_sweep_reached_the_routes(client):
    """Guard against a vacuous sweep: B sees the task, and most routes answer A."""
    r = await client.get(f"/api/tasks/{SOLO_TASK_ID}", headers=_headers(B_ID, B_DEVICE))
    assert r.status_code == 200 and SOLO_TITLE in r.text, r.text

    results = await _sweep(client)
    answered = [p for p, s, _ in results if s is not None and s < 400]
    unreached = [f"{s} {p}" for p, s, _ in results if s is None or s >= 400]
    print("\nGET routes not answered for A (not swept):\n  " + "\n  ".join(unreached))
    assert len(answered) >= 30, f"only {len(answered)} routes answered A"
