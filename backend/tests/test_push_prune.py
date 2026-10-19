"""A token FCM declares unregistered is deleted; any other failure keeps it.

Until 2026-10-18 nothing removed a dead token: three of Quentin's four were refused
`UnregisteredError` on every send, forever. Firebase is replaced by a stub answering, in
token order, one success, one `UnregisteredError` and one other error.

Paths, and what covers each:
- `UnregisteredError` prunes, success and other errors keep — this test, through
  `send_data_message_to_users`; the completion paths send through the same
  `_send_and_prune`.
- not covered: the delete itself failing (logged `push_prune_failed`), read only.
"""

from __future__ import annotations

import uuid
from types import SimpleNamespace

import pytest
from sqlalchemy import select
from structlog.testing import capture_logs

from tests.conftest import TEST_USER_ID


@pytest.mark.asyncio
async def test_only_unregistered_tokens_are_pruned(monkeypatch):
    import firebase_admin
    from firebase_admin import messaging

    from app.database import async_session
    from app.models.notification import NotificationToken
    from app.services import push

    alive, dead, flaky = (f"pytest-{name}-{uuid.uuid4().hex}" for name in ("alive", "dead", "flaky"))
    errors = {alive: None, dead: messaging.UnregisteredError("gone"), flaky: ValueError("busy")}

    def fake_send(message):
        responses = [SimpleNamespace(exception=errors.get(tok)) for tok in message.tokens]
        failed = sum(1 for r in responses if r.exception)
        return SimpleNamespace(
            success_count=len(responses) - failed, failure_count=failed, responses=responses)

    monkeypatch.setattr(firebase_admin, "_apps", {"[DEFAULT]": object()})
    monkeypatch.setattr(messaging, "send_each_for_multicast", fake_send)

    user = uuid.UUID(TEST_USER_ID)
    async with async_session() as session:
        session.add_all([NotificationToken(user_id=user, fcm_token=t) for t in errors])
        await session.commit()
        try:
            with capture_logs() as logs:
                await push.send_data_message_to_users(session, [user], {"type": "pytest"})
            async with async_session() as check:
                left = set((await check.execute(
                    select(NotificationToken.fcm_token)
                    .where(NotificationToken.fcm_token.in_(list(errors)))
                )).scalars())
        finally:
            async with async_session() as cleanup:
                rows = (await cleanup.execute(
                    select(NotificationToken).where(NotificationToken.fcm_token.in_(list(errors)))
                )).scalars().all()
                for row in rows:
                    await cleanup.delete(row)
                await cleanup.commit()

    assert left == {alive, flaky}, left
    pruned = [e for e in logs if e["event"] == "push_pruned"]
    assert len(pruned) == 1 and pruned[0]["tokens"] == 1, logs
    assert dead not in repr(logs), "the FCM token must never reach the logs"
