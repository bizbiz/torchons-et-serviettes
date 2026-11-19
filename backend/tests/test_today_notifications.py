"""« À faire aujourd'hui » notifications: one grouped announcement, reminders at Paris hours.

Spec: docs/plans/a-faire-aujourdhui.md § Notifications. Household of tests/today_helpers.py.
Firebase is stubbed: every multicast is captured with its tokens and text. Time is crossed by
passing a later `now` to the jobs, never by sleeping; the delegation's own `created_at` is the
server's clock at insertion, so each `now` below is taken right after the inserts.

PATHS by which a recipient could be told wrongly (or not told):
  (1) grouped: nothing inside the window, one message per (author, recipient) after it ..... COVERED
  (2) grouped: a delegation cancelled inside the window is not announced ................... COVERED
  (3) grouped: announced once, never again on the next pass (state in the database) ....... COVERED
  (4) reminders: sent when the last notification is older than 1 h, not when younger ...... COVERED
  (5) reminders: personal switch off ........................................................ COVERED
  (6) reminders fire at 12, 16 and 20 h PARIS time, summer and winter (the trigger) ........ COVERED
  non couvert : the scheduler itself calling the jobs (app/main.py), not started under pytest;
                the trigger it registers is the one (6) reads.
  non couvert : a hidden task's title in the text: same `hidden_among` filter as the sync
                completions (tests/test_sync_completion_push.py covers that helper's use).
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

import pytest
import pytest_asyncio

from app.services import delegation_push as dp
from tests.today_helpers import (
    ALICE, BOB, CAROL, cancel, delegate, drop_household_tokens, new_task, reset_state,
    seed_household, to_household,
)


@pytest_asyncio.fixture(scope="module", autouse=True)
async def household(client):
    await seed_household()
    yield
    await drop_household_tokens()


@pytest_asyncio.fixture(autouse=True)
async def clean_state():
    await reset_state()
    yield
    await reset_state()


@pytest.fixture
def sent(monkeypatch):
    import firebase_admin
    from firebase_admin import messaging

    captured = []

    def fake_send(message):
        tokens = list(message.tokens or []) + list(message.fids or [])
        captured.append((tokens, message.notification.title, message.notification.body))
        return SimpleNamespace(success_count=len(tokens), failure_count=0, responses=[])

    monkeypatch.setattr(firebase_admin, "_apps", {"[DEFAULT]": object()})
    monkeypatch.setattr(messaging, "send_each_for_multicast", fake_send)
    return captured


async def _announce(now: datetime) -> None:
    from app.database import async_session

    async with async_session() as session:
        await dp.announce_due_delegations(session, now=now)


async def _remind(now: datetime) -> None:
    from app.database import async_session

    async with async_session() as session:
        await dp.send_today_reminders(session, now=now)


def _after_window() -> datetime:
    return datetime.now(timezone.utc) + dp.ANNOUNCE_QUIET_WINDOW + timedelta(seconds=1)


@pytest.mark.asyncio
async def test_one_grouped_announcement_per_recipient_after_the_window(client, sent):
    tasks = [await new_task(client, title, [ALICE, BOB, CAROL]) for title in ("Lessive", "Courses", "Poubelles")]
    for task in tasks:
        await delegate(client, task, [BOB])
    await delegate(client, await new_task(client, "Vitres", [ALICE, CAROL]), [CAROL])
    await _announce(datetime.now(timezone.utc))
    assert to_household(sent) == [], "nothing inside the quiet window"
    await _announce(_after_window())
    assert to_household(sent) == [
        ("Bob", "À faire aujourd'hui", "Alice a mis 3 tâches à faire aujourd'hui : Lessive, Courses et Poubelles"),
        ("Carol", "À faire aujourd'hui", "Alice a mis une tâche à faire aujourd'hui : Vitres"),
    ]
    sent.clear()
    await _announce(_after_window() + timedelta(hours=1))
    assert to_household(sent) == [], "announced once"


@pytest.mark.asyncio
async def test_a_task_cancelled_inside_the_window_is_not_announced(client, sent):
    kept = await new_task(client, "Lit", [ALICE, BOB])
    dropped = await new_task(client, "Four", [ALICE, BOB])
    await delegate(client, kept, [BOB])
    dropped_id, _ = await delegate(client, dropped, [BOB])
    await cancel(client, dropped_id)
    await _announce(_after_window())
    assert to_household(sent) == [("Bob", "À faire aujourd'hui", "Alice a mis une tâche à faire aujourd'hui : Lit")]


@pytest.mark.asyncio
async def test_reminders_wait_one_hour_after_the_last_notification(client, sent):
    await delegate(client, await new_task(client, "Plantes", [ALICE, BOB]), [BOB])
    announced_at = _after_window()
    await _announce(announced_at)
    sent.clear()
    await _remind(announced_at + timedelta(minutes=59))
    assert to_household(sent) == [], "grouped notification less than an hour ago"
    await _remind(announced_at + timedelta(minutes=61))
    assert to_household(sent) == [("Bob", "À faire aujourd'hui", "Il te reste une tâche à faire aujourd'hui : Plantes")]


@pytest.mark.asyncio
async def test_reminders_follow_the_personal_switch(client, sent):
    from sqlalchemy import update

    from app.database import async_session
    from app.models.user_settings import UserSettings
    import uuid

    await delegate(client, await new_task(client, "Salle de bain", [ALICE, BOB]), [BOB])
    async with async_session() as session:
        await session.execute(
            update(UserSettings).where(UserSettings.user_id == uuid.UUID(BOB)).values(notif_today_reminders=False)
        )
        await session.commit()
    await _remind(_after_window() + timedelta(hours=2))
    assert to_household(sent) == []


@pytest.mark.parametrize("utc_after, paris_hours", [
    ("2026-07-01T00:00:00+00:00", ["12:00", "16:00", "20:00"]),  # CEST, UTC+2
    ("2026-01-15T00:00:00+00:00", ["12:00", "16:00", "20:00"]),  # CET, UTC+1
    ("2026-03-29T00:00:00+00:00", ["12:00", "16:00", "20:00"]),  # switch to summer time that night
])
def test_reminders_fire_at_noon_four_and_eight_paris_time(utc_after, paris_hours):
    from zoneinfo import ZoneInfo

    trigger = dp.reminder_trigger()
    moment = datetime.fromisoformat(utc_after)
    fired = []
    for _ in range(3):
        moment = trigger.get_next_fire_time(None, moment)
        fired.append(moment)
        moment = moment + timedelta(seconds=1)
    assert [f.astimezone(ZoneInfo("Europe/Paris")).strftime("%H:%M") for f in fired] == paris_hours
    utc_hours = {f.astimezone(timezone.utc).hour for f in fired}
    assert utc_hours in ({10, 14, 18}, {11, 15, 19}), utc_hours
