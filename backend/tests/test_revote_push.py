"""A re-vote request reaches the target: queued, then ONE grouped notification.

POST /prompts/request-revote used to send nothing. Now it queues per (household, requester,
target) and `flush_idle_completions` sends one notification to the TARGET only once
SYNC_QUIET_WINDOW has passed. Firebase is stubbed; the window is crossed by passing a later
`now`, never by sleeping.

Paths: (1) one request: single text, target only; (2) three requests: ONE grouped text;
(3) before the window: nothing; (4) a solo task of the requester hidden from the target:
never named; (5) more than five titles: cut with an ellipsis.
Not covered: a restart inside the window (the in-memory queue is lost, documented in
push.py); the Android side (bypass of the 2 h tolerance, fetch on resume).
The clock only enters through the flush `now` argument, shifted past the window.
"""

from __future__ import annotations

import uuid
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

import pytest

from app.services import push
from tests.module_helpers import house

WEEKLY = {"type": "interval", "every_n_days": 7}


@pytest.fixture
def sent(monkeypatch):
    """The multicasts sent, as (tokens, title, body); Firebase is never reached."""
    import firebase_admin
    from firebase_admin import messaging

    captured = []

    def fake_send(message):
        captured.append((list(message.tokens), message.notification.title, message.notification.body))
        return SimpleNamespace(success_count=len(message.tokens), failure_count=0, responses=[])

    monkeypatch.setattr(firebase_admin, "_apps", {"[DEFAULT]": object()})
    monkeypatch.setattr(messaging, "send_each_for_multicast", fake_send)
    push._pending_revotes.clear()
    yield captured
    push._pending_revotes.clear()


@asynccontextmanager
async def listening_house():
    """A throwaway household whose two members each have one FCM token."""
    from sqlalchemy import delete

    from app.database import async_session
    from app.models.notification import NotificationToken

    async with house() as h:
        async with async_session() as session:
            for i, uid in enumerate(h.user_ids):
                session.add(NotificationToken(user_id=uid, fcm_token=f"pytest-revote-{i}-{uuid.uuid4().hex}"))
            await session.commit()
        try:
            yield h
        finally:
            async with async_session() as session:
                await session.execute(
                    delete(NotificationToken).where(NotificationToken.user_id.in_(h.user_ids))
                )
                await session.commit()


async def _task(h, title, solo_of=None):
    from app.database import async_session
    from app.models.task import Task
    from app.models.task_assignment import TaskAssignment

    task_id = uuid.uuid4()
    async with async_session() as session:
        session.add(Task(id=task_id, foyer_id=h.foyer_id, title=title, recurrence_rule=WEEKLY))
        await session.flush()
        if solo_of is not None:
            session.add(TaskAssignment(task_id=task_id, user_id=h.user_ids[solo_of]))
        await session.commit()
    return task_id


async def _ask(client, h, task_id, kind):
    r = await client.post(
        "/api/prompts/request-revote",
        json={"target_user_id": str(h.user_ids[1]), "task_id": str(task_id), "kind": kind},
        headers=h.headers(0),
    )
    assert r.status_code == 200, r.text


async def _flush(after_window=True):
    now = datetime.now(timezone.utc)
    if after_window:
        now += push.SYNC_QUIET_WINDOW + timedelta(seconds=1)
    return await push.flush_idle_completions(now=now)


async def _tokens_of(h, member):
    from sqlalchemy import select

    from app.database import async_session
    from app.models.notification import NotificationToken

    async with async_session() as session:
        rows = await session.execute(
            select(NotificationToken.fcm_token).where(NotificationToken.user_id == h.user_ids[member])
        )
        return [t for (t,) in rows.all()]


async def test_single_request_notifies_the_target_only(client, sent):
    async with listening_house() as h:
        await _ask(client, h, h.task_id, "effort")
        assert sent == []  # queued, not sent at commit time
        await _flush(after_window=False)
        assert sent == []  # window not over
        await _flush()
        assert len(sent) == 1
        tokens, title, body = sent[0]
        assert tokens == await _tokens_of(h, 1)
        assert body == "M0 te demande de revoter la pénibilité de cn task"


async def test_three_requests_make_one_grouped_notification(client, sent):
    async with listening_house() as h:
        a, b = await _task(h, "Aspirateur"), await _task(h, "Vitres")
        await _ask(client, h, a, "frequency")
        await _ask(client, h, b, "frequency")
        await _ask(client, h, h.task_id, "effort")
        await _flush()
        assert len(sent) == 1
        assert sent[0][2] == "M0 te demande 3 revotes : Aspirateur, Vitres, cn task"


async def test_requester_solo_task_is_never_named(client, sent):
    async with listening_house() as h:
        solo = await _task(h, "Secret", solo_of=0)
        await _ask(client, h, solo, "effort")
        await _ask(client, h, h.task_id, "effort")
        await _flush()
        assert len(sent) == 1
        assert "Secret" not in sent[0][2]
        assert sent[0][2] == "M0 te demande de revoter la pénibilité de cn task"


def test_body_cuts_after_five_titles():
    body = push.build_revote_body("Q", [("effort", f"T{i}") for i in range(7)])
    assert body == "Q te demande 7 revotes : T0, T1, T2, T3, T4…"
