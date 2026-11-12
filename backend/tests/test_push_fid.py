"""Push targets are Firebase Installation IDs (FID) for the app since 2026-11-12, legacy FCM
tokens for the APKs before it — both served side by side until the old APKs are gone.

FCM deprecated `MulticastMessage.tokens` for `fids`. An FID is NOT a token: the app registers
it through `onRegistered` (firebase-messaging 25.1+) and sends it as `fid`.

PATHS by which a phone could miss a notification, or get two:
  (1) a send mixing tokens and FIDs: each target in its own field, dead ones pruned
      in the order FCM answers (tokens, then fids) ....................................... COVERED
  (2) /notifications/register with `fid`: stored as kind "fid", the device's old token
      row dropped (it would notify the same phone twice) ................................... COVERED
  (3) /notifications/register with `fcm_token` (APKs in circulation): still kind "token" .... COVERED
  (4) a body with neither, or both .......................................................... COVERED
  non couvert : the real FCM answer to an FID — seen on a phone, not in a test.
"""

from __future__ import annotations

import uuid
from types import SimpleNamespace

import pytest
from sqlalchemy import delete, select

from tests.conftest import TEST_USER_ID


async def _cleanup(values):
    from app.database import async_session
    from app.models.notification import NotificationToken

    async with async_session() as session:
        await session.execute(delete(NotificationToken).where(NotificationToken.fcm_token.in_(values)))
        await session.commit()


@pytest.mark.asyncio
async def test_a_mixed_send_uses_both_fields_and_prunes_a_dead_fid(monkeypatch):
    import firebase_admin
    from firebase_admin import messaging

    from app.database import async_session
    from app.models.notification import NotificationToken
    from app.services import push

    tok, fid_alive, fid_dead = (f"pytest-{n}-{uuid.uuid4().hex}" for n in ("tok", "fid", "fiddead"))
    seen = {}

    def fake_send(message):
        seen["tokens"], seen["fids"] = message.tokens, message.fids
        order = (message.tokens or []) + (message.fids or [])
        errors = {fid_dead: messaging.UnregisteredError("gone")}
        responses = [SimpleNamespace(exception=errors.get(t)) for t in order]
        failed = sum(1 for r in responses if r.exception)
        return SimpleNamespace(success_count=len(order) - failed, failure_count=failed, responses=responses)

    monkeypatch.setattr(firebase_admin, "_apps", {"[DEFAULT]": object()})
    monkeypatch.setattr(messaging, "send_each_for_multicast", fake_send)

    user = uuid.UUID(TEST_USER_ID)
    values = [tok, fid_alive, fid_dead]
    async with async_session() as session:
        session.add_all([
            NotificationToken(user_id=user, fcm_token=tok, kind="token"),
            NotificationToken(user_id=user, fcm_token=fid_alive, kind="fid"),
            NotificationToken(user_id=user, fcm_token=fid_dead, kind="fid"),
        ])
        await session.commit()
        try:
            await push.send_data_message_to_users(session, [user], {"type": "pytest"})
            async with async_session() as check:
                left = set((await check.execute(
                    select(NotificationToken.fcm_token).where(NotificationToken.fcm_token.in_(values))
                )).scalars())
        finally:
            await _cleanup(values)

    assert tok in (seen["tokens"] or []) and tok not in (seen["fids"] or []), seen
    assert {fid_alive, fid_dead} <= set(seen["fids"] or []), seen
    assert left == {tok, fid_alive}, left


@pytest.mark.asyncio
async def test_registering_an_fid_replaces_the_devices_token(client, auth_headers):
    from app.database import async_session
    from app.models.notification import NotificationToken

    tok, fid = f"pytest-oldtok-{uuid.uuid4().hex}", f"pytest-fid-{uuid.uuid4().hex}"
    try:
        old = await client.post("/api/notifications/register", headers=auth_headers,
                                json={"fcm_token": tok, "device_name": "pytest"})
        assert old.status_code == 200, old.text
        new = await client.post("/api/notifications/register", headers=auth_headers,
                                json={"fid": fid, "device_name": "pytest"})
        assert new.status_code == 200, new.text
        async with async_session() as check:
            rows = {r.fcm_token: r.kind for r in (await check.execute(
                select(NotificationToken).where(NotificationToken.fcm_token.in_([tok, fid]))
            )).scalars()}
    finally:
        await _cleanup([tok, fid])
    assert rows == {fid: "fid"}, rows


@pytest.mark.asyncio
@pytest.mark.parametrize("body", [{}, {"fcm_token": "a", "fid": "b"}])
async def test_neither_or_both_is_refused(client, auth_headers, body):
    resp = await client.post("/api/notifications/register", headers=auth_headers, json=body)
    assert resp.status_code == 422, resp.text
