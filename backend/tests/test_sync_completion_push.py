"""A completion arriving through /sync is QUEUED, then announced once its group went quiet.

Every online tick on the phone is its own /sync call, so the server sends nothing at commit
time: it queues per (household, recorder) and `flush_idle_completions` sends one notification
per (recipient, kind, author) once SYNC_QUIET_WINDOW has passed without a new completion.
Firebase is stubbed: every multicast sent is captured, with its tokens and text.

Member D records, member C listens (one token). Paths covered:
  (1) two completions in one call: ONE notification to C, naming both, none to D;
  (2) a completion dated two days back NOW notifies, with its date in the text;
  (3) a completion of D's solo task: none (C may not see it); in a mixed call its title
      never reaches C's text;
  (4) C with `notif_solo` off: none;
  (5) the same op replayed: none the second time (already applied);
  (6) several /sync calls inside the window: nothing before it ends, ONE grouped send after;
  (7) D ticks a task in C's name: C hears « D a noté que tu as fait » only with
      `notif_collective_with_me` on, and D hears nothing.
  non couvert : the REST routes, which keep their own path (`send_completion_event`).
The quiet window is crossed by passing a later `now` to the flush, never by sleeping.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

import pytest
import pytest_asyncio
from sqlalchemy import select

from app.auth import create_token
from app.services import push

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


async def _set_c_flags(solo: bool, with_me: bool = False):
    from app.database import async_session
    from app.models.user_settings import UserSettings

    async with async_session() as session:
        row = (await session.execute(
            select(UserSettings).where(UserSettings.user_id == uuid.UUID(C_ID))
        )).scalar_one_or_none()
        if row is None:
            row = UserSettings(user_id=uuid.UUID(C_ID))
            session.add(row)
        row.notif_solo = solo
        row.notif_collective_with_me = with_me
        await session.commit()


async def _set_c_listening(on: bool):
    await _set_c_flags(on, False)


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
    push._pending.clear()
    yield captured
    push._pending.clear()


async def _flush_after_window():
    """Flush as if the quiet window had passed, without sleeping."""
    later = datetime.now(timezone.utc) + push.SYNC_QUIET_WINDOW + timedelta(seconds=1)
    return await push.flush_idle_completions(now=later)


async def _sync(client, ops):
    body = {"device_id": D_DEVICE, "last_server_version": 0, "changes": ops}
    r = await client.post("/api/sync", json=body, headers=_headers())
    assert r.status_code == 200 and not r.json()["conflicts"], r.text
    return r


async def _new_task(client, title, assignees):
    task_id = str(uuid.uuid4())
    await _sync(client, [_op("tasks", task_id, {"title": title, "assigned_to_ids": assignees})])
    return task_id


def _completion(task_id, completed_at=None, op_id=None, by=D_ID):
    data = {"task_id": task_id, "completed_by": by}
    if completed_at:
        data["completed_at"] = completed_at
    return _op("task_completions", str(uuid.uuid4()), data, op_id)


@pytest.mark.asyncio
async def test_two_completions_in_one_call_make_one_notification(client, sent):
    await _set_c_listening(True)
    first = await _new_task(client, "Vaisselle", [C_ID, D_ID])
    second = await _new_task(client, "Linge", [C_ID, D_ID])
    await _sync(client, [_completion(first), _completion(second)])
    assert sent == [], "nothing is sent at commit time"
    await _flush_after_window()
    assert sent == [([C_TOKEN], "Tâches complétées !", "push-d a fait 2 tâches : Vaisselle, Linge")]


@pytest.mark.asyncio
async def test_an_old_completion_notifies_with_its_date(client, sent):
    await _set_c_listening(True)
    task = await _new_task(client, "Vitres", [C_ID, D_ID])
    old_moment = datetime.now(timezone.utc) - timedelta(days=2)
    await _sync(client, [_completion(task, completed_at=old_moment.isoformat())])
    await _flush_after_window()
    day = f"{push._local_date(old_moment):%d/%m}"
    assert sent == [([C_TOKEN], "Tâche complétée !", f"push-d a fait : Vitres ({day})")]


@pytest.mark.asyncio
async def test_a_solo_completion_notifies_nobody(client, sent):
    await _set_c_listening(True)
    task = await _new_task(client, "Soin perso", [D_ID])
    await _sync(client, [_completion(task)])
    await _flush_after_window()
    assert sent == []


@pytest.mark.asyncio
async def test_a_hidden_solo_task_never_appears_in_the_text(client, sent):
    await _set_c_listening(True)
    shared = await _new_task(client, "Courses", [C_ID, D_ID])
    secret = await _new_task(client, "Cadeau surprise", [D_ID])
    await _sync(client, [_completion(shared), _completion(secret)])
    await _flush_after_window()
    assert sent == [([C_TOKEN], "Tâche complétée !", "push-d a fait : Courses")]


@pytest.mark.asyncio
async def test_a_muted_member_is_not_notified(client, sent):
    await _set_c_listening(False)
    task = await _new_task(client, "Poubelles", [C_ID, D_ID])
    await _sync(client, [_completion(task)])
    await _flush_after_window()
    assert sent == []


@pytest.mark.asyncio
async def test_a_replayed_op_notifies_once(client, sent):
    await _set_c_listening(True)
    task = await _new_task(client, "Aspirateur", [C_ID, D_ID])
    op_id = str(uuid.uuid4())
    op = _completion(task, op_id=op_id)
    await _sync(client, [op])
    await _sync(client, [op])
    await _flush_after_window()
    assert len(sent) == 1, sent


@pytest.mark.asyncio
async def test_calls_inside_the_window_make_one_grouped_send(client, sent):
    await _set_c_listening(True)
    tasks = [await _new_task(client, f"Piece {n}", [C_ID, D_ID]) for n in range(3)]
    for t in tasks:  # one /sync call per tick, as the phone does
        await _sync(client, [_completion(t)])
    assert await push.flush_idle_completions() == 0  # window not over: nothing leaves
    assert sent == []
    await _flush_after_window()
    assert sent == [([C_TOKEN], "Tâches complétées !", "push-d a fait 3 tâches : Piece 0, Piece 1, Piece 2")]


@pytest.mark.asyncio
async def test_ticked_in_my_name_reaches_the_completer_only_when_flag_is_on(client, sent):
    task = await _new_task(client, "Salle de bain", [C_ID, D_ID])
    await _set_c_flags(solo=False, with_me=False)
    await _sync(client, [_completion(task, by=C_ID)])
    await _flush_after_window()
    assert sent == []

    await _set_c_flags(solo=False, with_me=True)
    await _sync(client, [_completion(task, by=C_ID)])
    await _flush_after_window()
    assert sent == [([C_TOKEN], "Tâche complétée !", "push-d a noté que tu as fait : Salle de bain")]
