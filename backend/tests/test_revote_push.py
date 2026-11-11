"""A re-vote request notifies its target at once.

POST /prompts/request-revote used to send nothing. It now sends one notification to the TARGET
as soon as the request is committed, with no grouping window (Quentin, 2026-11-11: a re-vote
request must be immediate). Firebase is stubbed.

Paths: (1) one request: sent before the route returns, target only; (2) two requests: two
notifications; (3) a solo task of the requester, hidden from the target: never named.
Not covered: the Android side (bypass of the 2 h tolerance, refresh after a dismissal).
"""

from __future__ import annotations

import uuid
from contextlib import asynccontextmanager
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
    yield captured


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


async def _tokens_of(h, member):
    from sqlalchemy import select

    from app.database import async_session
    from app.models.notification import NotificationToken

    async with async_session() as session:
        rows = await session.execute(
            select(NotificationToken.fcm_token).where(NotificationToken.user_id == h.user_ids[member])
        )
        return [t for (t,) in rows.all()]


async def test_single_request_notifies_the_target_at_once(client, sent):
    async with listening_house() as h:
        await _ask(client, h, h.task_id, "effort")
        assert len(sent) == 1  # sent before the route returned: no window, no scheduler
        tokens, title, body = sent[0]
        assert tokens == await _tokens_of(h, 1)
        assert title == "Demande de revote"
        assert body == "M0 te demande de revoter la pénibilité de cn task"


async def test_two_requests_make_two_notifications(client, sent):
    async with listening_house() as h:
        a = await _task(h, "Aspirateur")
        await _ask(client, h, a, "frequency")
        await _ask(client, h, h.task_id, "effort")
        assert [b for _, _, b in sent] == [
            "M0 te demande de revoter la fréquence de Aspirateur",
            "M0 te demande de revoter la pénibilité de cn task",
        ]


async def test_the_notification_names_the_task_contexts(client, sent):
    """Two « Passer l'aspirateur » are told apart by their contexts, main one first."""
    from app.database import async_session
    from app.models.context import Context, TaskContext

    async with listening_house() as h:
        task_id = await _task(h, "Passer l'aspirateur")
        async with async_session() as session:
            salon = Context(foyer_id=h.foyer_id, name="Salon")
            menage = Context(foyer_id=h.foyer_id, name="Ménage")
            session.add_all([salon, menage])
            await session.flush()
            session.add_all([
                TaskContext(task_id=task_id, context_id=menage.id, position=1),
                TaskContext(task_id=task_id, context_id=salon.id, position=0),
            ])
            await session.commit()
        await _ask(client, h, task_id, "frequency")
        assert sent[0][2] == "M0 te demande de revoter la fréquence de Passer l'aspirateur (Salon, Ménage)"


async def test_requester_solo_task_is_never_named(client, sent):
    async with listening_house() as h:
        solo = await _task(h, "Secret", solo_of=0)
        r = await client.post(
            "/api/prompts/request-revote",
            json={"target_user_id": str(h.user_ids[1]), "task_id": str(solo), "kind": "effort"},
            headers=h.headers(0),
        )
        assert all("Secret" not in b for _, _, b in sent), (r.status_code, sent)
