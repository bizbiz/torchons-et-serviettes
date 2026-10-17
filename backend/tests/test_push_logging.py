"""A push that fails must leave a trace in the logs.

Until 2026-10-17, services/push.py swallowed every send failure (`except Exception: pass`):
a notification that never left looked exactly like one that did. Firebase is replaced by a
stub that answers one success and one failure, and the log must say so, without the token.

Paths by which a push can leave no trace, and what covers each:
- a partial failure answered by FCM (`failure_count` > 0) — this test, through
  `send_data_message_to_users`; `send_completion_event` sends through the same
  `_send_multicast`, so the outcome logging is shared.
- not covered: `send_each_for_multicast` raising (logged `push_failed`), read only.
- not covered: the completion path stopping early (`push_skipped`: Firebase not
  initialised, every recipient muted by their settings, no token) — read only, it
  needs a second household member and their settings.
"""

from __future__ import annotations

import uuid
from types import SimpleNamespace

import pytest
from structlog.testing import capture_logs

from tests.conftest import TEST_USER_ID


@pytest.mark.asyncio
async def test_a_failed_push_is_logged(monkeypatch):
    import firebase_admin
    from firebase_admin import messaging

    from app.database import async_session
    from app.models.notification import NotificationToken
    from app.services import push

    token = f"pytest-{uuid.uuid4().hex}"
    fake = SimpleNamespace(
        success_count=1,
        failure_count=1,
        responses=[
            SimpleNamespace(exception=None),
            SimpleNamespace(exception=ValueError("unregistered")),
        ],
    )
    monkeypatch.setattr(firebase_admin, "_apps", {"[DEFAULT]": object()})
    monkeypatch.setattr(messaging, "send_each_for_multicast", lambda message: fake)

    async with async_session() as session:
        row = NotificationToken(user_id=uuid.UUID(TEST_USER_ID), fcm_token=token)
        session.add(row)
        await session.commit()
        try:
            with capture_logs() as logs:
                await push.send_data_message_to_users(
                    session, [uuid.UUID(TEST_USER_ID)], {"type": "pytest"})
        finally:
            await session.delete(row)
            await session.commit()

    sent = [e for e in logs if e["event"] == "push_sent"]
    assert len(sent) == 1, logs
    assert sent[0]["failed"] == 1 and sent[0]["ok"] == 1, sent
    assert sent[0]["errors"] == ["ValueError"], sent
    assert token not in repr(logs), "the FCM token must never reach the logs"
