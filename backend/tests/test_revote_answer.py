"""The requester hears of the answer to a re-vote request, and of a plain closing.

Covered: the vote snapshot stored at request time, `notified` on the request, the three texts
(changed, confirmed, dismissed) for effort and frequency, the data payload, no push for a task
hidden from the requester, no push for a prompt without requester. Firebase is stubbed.
Not covered: the Android side (dialog, registry), real FCM delivery.
"""

from __future__ import annotations

import uuid
from types import SimpleNamespace

import pytest

from tests.test_revote_push import _ask, _task, listening_house
from tests.module_helpers import house


@pytest.fixture
def sent(monkeypatch):
    """The multicasts sent, as (title, body, data); `accepted` sets what FCM answers."""
    import firebase_admin
    from firebase_admin import messaging

    class Sent(list):
        state = None

    captured = Sent()
    state = SimpleNamespace(accepted=True)

    def fake_send(message):
        captured.append((message.notification.title, message.notification.body, dict(message.data or {})))
        ok = len(message.tokens) if state.accepted else 0
        return SimpleNamespace(success_count=ok, failure_count=len(message.tokens) - ok, responses=[])

    monkeypatch.setattr(firebase_admin, "_apps", {"[DEFAULT]": object()})
    monkeypatch.setattr(messaging, "send_each_for_multicast", fake_send)
    captured.state = state
    yield captured


async def _vote(h, task_id, member, effort=None, days=None):
    from app.database import async_session
    from app.models.task_effort import TaskEffort
    from app.models.task_frequency_vote import TaskFrequencyVote

    async with async_session() as session:
        if effort is not None:
            session.add(TaskEffort(task_id=task_id, user_id=h.user_ids[member], points=effort))
        if days is not None:
            session.add(TaskFrequencyVote(
                task_id=task_id, user_id=h.user_ids[member],
                days_per_month={str(m): days for m in range(1, 13)},
            ))
        await session.commit()


async def _request(client, h, task_id, kind):
    r = await client.post(
        "/api/prompts/request-revote",
        json={"target_user_id": str(h.user_ids[1]), "task_id": str(task_id), "kind": kind},
        headers=h.headers(0),
    )
    assert r.status_code == 200, r.text
    return r.json()


async def _resolve(client, h, prompt_id, resolution, payload=None):
    r = await client.post(
        f"/api/prompts/{prompt_id}/resolve",
        json={"resolution": resolution, "payload": payload},
        headers=h.headers(1),
    )
    assert r.status_code == 200, r.text


async def _prompt_context(prompt_id):
    from app.database import async_session
    from app.models.user_prompt import UserPrompt

    async with async_session() as session:
        return (await session.get(UserPrompt, uuid.UUID(prompt_id))).context


async def test_snapshot_holds_the_targets_current_vote(client, sent):
    async with listening_house() as h:
        await _vote(h, h.task_id, 1, effort=3)
        got = await _request(client, h, h.task_id, "effort")
        assert (await _prompt_context(got["prompt_id"]))["vote_at_request"] == 3
        other = await _task(h, "Sans vote")
        got = await _request(client, h, other, "effort")
        assert (await _prompt_context(got["prompt_id"]))["vote_at_request"] is None
        await _vote(h, other, 1, days=7)
        got = await _request(client, h, other, "frequency")
        snap = (await _prompt_context(got["prompt_id"]))["vote_at_request"]
        assert snap == {str(m): 7 for m in range(1, 13)}


async def test_notified_follows_what_fcm_accepted(client, sent):
    async with listening_house() as h:
        assert (await _request(client, h, h.task_id, "effort"))["notified"] is True
        sent.state.accepted = False
        other = await _task(h, "Autre")
        assert (await _request(client, h, other, "effort"))["notified"] is False


async def test_notified_false_without_any_device(client, sent):
    async with house() as h:  # no token for anybody
        assert (await _request(client, h, h.task_id, "effort"))["notified"] is False
        assert sent == []


async def test_effort_changed_text_and_data(client, sent):
    async with listening_house() as h:
        await _vote(h, h.task_id, 1, effort=3)
        pid = (await _request(client, h, h.task_id, "effort"))["prompt_id"]
        sent.clear()
        await _resolve(client, h, pid, "ANSWERED", {"points": 2})
        assert len(sent) == 1
        title, body, data = sent[0]
        assert title == "Revote : réponse reçue"
        assert body == (
            "M1 a répondu à ta demande de revote : la pénibilité de cn task, de 15 à 10 min."
        )
        assert data == {
            "type": "revote_answered", "task_id": str(h.task_id), "kind": "effort",
            "resolution": "answered", "responder_user_id": str(h.user_ids[1]),
            "responder_name": "M1", "old_value": "3", "new_value": "2",
        }


async def test_new_value_read_from_the_database_for_an_old_app(client, sent):
    """An APK that sends no payload: the vote is read where the REST call wrote it."""
    async with listening_house() as h:
        await _vote(h, h.task_id, 1, effort=3)
        pid = (await _request(client, h, h.task_id, "effort"))["prompt_id"]
        from sqlalchemy import update

        from app.database import async_session
        from app.models.task_effort import TaskEffort

        async with async_session() as session:
            await session.execute(update(TaskEffort).where(TaskEffort.task_id == h.task_id).values(points=4))
            await session.commit()
        sent.clear()
        await _resolve(client, h, pid, "ANSWERED")
        assert sent[0][1].endswith("la pénibilité de cn task, de 15 à 20 min.")


async def test_unchanged_vote_is_confirmed_not_a_range(client, sent):
    async with listening_house() as h:
        await _vote(h, h.task_id, 1, effort=3)
        pid = (await _request(client, h, h.task_id, "effort"))["prompt_id"]
        sent.clear()
        await _resolve(client, h, pid, "ANSWERED", {"points": 3})
        body = sent[0][1]
        assert body == "M1 a répondu à ta demande de revote : vote confirmé, 15min pour cn task."
        assert " à 15" not in body


async def test_frequency_changed_and_unchanged(client, sent):
    async with listening_house() as h:
        first = await _task(h, "cn task")  # the house task has no votable rule
        await _vote(h, first, 1, days=7)
        pid = (await _request(client, h, first, "frequency"))["prompt_id"]
        sent.clear()
        await _resolve(client, h, pid, "ANSWERED", {"days_per_month": {str(m): 14 for m in range(1, 13)}})
        assert sent[0][1] == (
            "M1 a répondu à ta demande de revote : la fréquence de cn task, de 1 sem. à 2 sem."
        )
        assert sent[0][2]["old_value"] == ",".join(["7"] * 12)
        assert sent[0][2]["new_value"] == ",".join(["14"] * 12)
        other = await _task(h, "Autre")
        await _vote(h, other, 1, days=30)
        pid = (await _request(client, h, other, "frequency"))["prompt_id"]
        sent.clear()
        await _resolve(client, h, pid, "ANSWERED", {"days_per_month": {str(m): 30 for m in range(1, 13)}})
        assert sent[0][1] == "M1 a répondu à ta demande de revote : vote confirmé, tous les 1 mois pour Autre."


async def test_dismissal_notifies_too(client, sent):
    async with listening_house() as h:
        await _vote(h, h.task_id, 1, effort=3)
        pid = (await _request(client, h, h.task_id, "effort"))["prompt_id"]
        sent.clear()
        await _resolve(client, h, pid, "DISMISSED")
        title, body, data = sent[0]
        assert body == "M1 a fermé ta demande de revote sans changer son vote (cn task)."
        assert data["resolution"] == "dismissed" and data["type"] == "revote_answered"


async def test_no_push_when_the_task_is_hidden_from_the_requester(client, sent):
    async with listening_house() as h:
        solo = await _task(h, "Secret", solo_of=1)  # solo task of the responder
        pid = (await _request(client, h, solo, "effort"))["prompt_id"]
        sent.clear()
        await _resolve(client, h, pid, "ANSWERED", {"points": 2})
        assert sent == []


async def test_no_push_for_a_prompt_without_requester(client, sent):
    from app.database import async_session
    from app.models.enums import PromptType
    from app.models.user_prompt import UserPrompt

    async with listening_house() as h:
        async with async_session() as session:
            p = UserPrompt(
                user_id=h.user_ids[1], foyer_id=h.foyer_id, type=PromptType.VOTE_EFFORT,
                context={"task_id": str(h.task_id), "kind": "effort"},
            )
            session.add(p)
            await session.commit()
            pid = str(p.id)
        await _resolve(client, h, pid, "ANSWERED", {"points": 2})
        assert sent == []
