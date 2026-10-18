"""A completion arriving through /sync notifies the household, once per member and per call.

Until 2026-10-18 only the REST routes (`POST /tasks/{id}/complete`, `/complete-collective`)
notified; the app records a plain completion through /sync, so it never reached anyone.
Firebase is stubbed: every multicast sent is captured, with its tokens and text.

Member D completes, member C listens (`notif_solo` on, one token). Paths covered:
  (1) two completions in one call: ONE notification to C, naming both, none to D;
  (2) a completion dated more than a day back: none;
  (3) a completion of D's solo task: none (C may not see it);
  (4) C with `notif_solo` off: none;
  (5) the same op replayed: none the second time (already applied).
  non couvert : a completion recorded by C on D's behalf (C, the recorder, is skipped), read only.
  non couvert : the REST routes, which keep their own path (`send_completion_event`).
Dates are relative to now (two days back, never the edge of the one-day window): the path
taken does not depend on the hour of the run.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

import pytest
import pytest_asyncio
from sqlalchemy import select

from app.auth import create_token

HOUSE_ID = "5020aaaa-0000-4000-8000-00000000f0c2"
C_ID = "5020aaaa-0000-4000-8000-0000000000c1"
D_ID = "5020aaaa-0000-4000-8000-0000000000d1"
D_DEVICE = str(uuid.uuid4())
C_TOKEN = f"pytest-sync-push-{uuid.uuid4().hex}"


def _headers():
    return {"Authorization": f"Bearer {create_token(D_ID, D_DEVICE, foyer_id=HOUSE_ID)}"}


def _op(table, row_id, data, op_id=None):
    return {
        "client_op_id": op_id or str(uuid.uuid4()), "table": table, "operation": "INSERT",
        "row_id": row_id, "data": data, "client_timestamp": datetime.now(timezone.utc).isoformat(),
    }


async def _set_c_listening(on: bool):
    from app.database import async_session
    from app.models.user_settings import UserSettings

    async with async_session() as session:
        row = (await session.execute(
            select(UserSettings).where(UserSettings.user_id == uuid.UUID(C_ID))
        )).scalar_one_or_none()
        if row is None:
            row = UserSettings(user_id=uuid.UUID(C_ID))
            session.add(row)
        row.notif_solo = on
        await session.commit()


@pytest_asyncio.fixture(scope="module", autouse=True)
async def seed_household(client):
    from app.database import async_session
    from app.models.foyer import Foyer
    from app.models.notification import NotificationToken
    from app.models.user import User
    from app.models.user_foyer import UserFoyer

    async with async_session() as session:
        if await session.get(Foyer, uuid.UUID(HOUSE_ID)) is None:
            session.add(Foyer(id=uuid.UUID(HOUSE_ID), name="Sync push household"))
            await session.flush()
        for uid, name in ((C_ID, "push-c"), (D_ID, "push-d")):
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
        token = NotificationToken(user_id=uuid.UUID(C_ID), fcm_token=C_TOKEN)
        session.add(token)
        await session.commit()
    yield
    async with async_session() as session:
        await session.delete(await session.merge(token))
        await session.commit()


@pytest.fixture
def sent(monkeypatch):
    """The multicasts sent, as (tokens, title, body); Firebase is never reached."""
    import firebase_admin
    from firebase_admin import messaging
    from types import SimpleNamespace

    captured = []

    def fake_send(message):
        captured.append((list(message.tokens), message.notification.title, message.notification.body))
        return SimpleNamespace(success_count=len(message.tokens), failure_count=0, responses=[])

    monkeypatch.setattr(firebase_admin, "_apps", {"[DEFAULT]": object()})
    monkeypatch.setattr(messaging, "send_each_for_multicast", fake_send)
    return captured


async def _sync(client, ops):
    body = {"device_id": D_DEVICE, "last_server_version": 0, "changes": ops}
    r = await client.post("/api/sync", json=body, headers=_headers())
    assert r.status_code == 200 and not r.json()["conflicts"], r.text
    return r


async def _new_task(client, title, assignees):
    task_id = str(uuid.uuid4())
    await _sync(client, [_op("tasks", task_id, {"title": title, "assigned_to_ids": assignees})])
    return task_id


def _completion(task_id, completed_at=None, op_id=None):
    data = {"task_id": task_id, "completed_by": D_ID}
    if completed_at:
        data["completed_at"] = completed_at
    return _op("task_completions", str(uuid.uuid4()), data, op_id)


@pytest.mark.asyncio
async def test_two_completions_in_one_call_make_one_notification(client, sent):
    await _set_c_listening(True)
    first = await _new_task(client, "Vaisselle", [C_ID, D_ID])
    second = await _new_task(client, "Linge", [C_ID, D_ID])
    await _sync(client, [_completion(first), _completion(second)])
    assert sent == [([C_TOKEN], "Taches completees !", "push-d a fait 2 tâches : Vaisselle, Linge")]


@pytest.mark.asyncio
async def test_a_completion_dated_two_days_back_notifies_nobody(client, sent):
    await _set_c_listening(True)
    task = await _new_task(client, "Vitres", [C_ID, D_ID])
    old = (datetime.now(timezone.utc) - timedelta(days=2)).isoformat()
    await _sync(client, [_completion(task, completed_at=old)])
    assert sent == []
    # Control: the same task completed now does notify.
    await _sync(client, [_completion(task)])
    assert sent == [([C_TOKEN], "Tache completee !", "push-d a fait : Vitres")]


@pytest.mark.asyncio
async def test_a_solo_completion_notifies_nobody(client, sent):
    await _set_c_listening(True)
    task = await _new_task(client, "Soin perso", [D_ID])
    await _sync(client, [_completion(task)])
    assert sent == []


@pytest.mark.asyncio
async def test_a_muted_member_is_not_notified(client, sent):
    await _set_c_listening(False)
    task = await _new_task(client, "Poubelles", [C_ID, D_ID])
    await _sync(client, [_completion(task)])
    assert sent == []


@pytest.mark.asyncio
async def test_a_replayed_op_notifies_once(client, sent):
    await _set_c_listening(True)
    task = await _new_task(client, "Aspirateur", [C_ID, D_ID])
    op_id = str(uuid.uuid4())
    op = _completion(task, op_id=op_id)
    await _sync(client, [op])
    await _sync(client, [op])
    assert len(sent) == 1, sent
