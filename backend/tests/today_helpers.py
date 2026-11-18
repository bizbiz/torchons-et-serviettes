"""Shared household and helpers of the « À faire aujourd'hui » tests (docs/plans/a-faire-aujourdhui.md).

Alice hands tasks; Bob and Carol receive them. Generic names: the repository is public.
Every helper goes through the real /sync and GET routes; Firebase is stubbed by `sent`.
The paths each test file covers, and those it leaves out, are listed in its own header
(test_today_delegations.py, test_today_notifications.py).
  non couvert : here, nothing; this file asserts nothing, it only drives the routes.
No wall clock here: the notification tests pass their own `now`.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone

from sqlalchemy import select, update

from app.auth import create_token

HOUSE_ID = "5030aaaa-0000-4000-8000-00000000f0d1"
ALICE = "5030aaaa-0000-4000-8000-0000000000a1"
BOB = "5030aaaa-0000-4000-8000-0000000000b1"
CAROL = "5030aaaa-0000-4000-8000-0000000000c1"
NAMES = {ALICE: "Alice", BOB: "Bob", CAROL: "Carol"}
DEVICES = {uid: str(uuid.uuid4()) for uid in NAMES}
TOKENS = {uid: f"pytest-today-{uid[-2:]}-{uuid.uuid4().hex}" for uid in NAMES}
# Ops are stamped with a fixed moment: no rule under test reads the client timestamp.
FIXED_TS = "2026-11-01T08:00:00+00:00"
LEFTOVER_END = datetime(2026, 1, 1, tzinfo=timezone.utc)


def headers(user_id: str) -> dict:
    return {"Authorization": f"Bearer {create_token(user_id, DEVICES[user_id], foyer_id=HOUSE_ID)}"}


def op(table: str, operation: str, row_id: str, data: dict | None = None) -> dict:
    return {
        "client_op_id": str(uuid.uuid4()), "table": table, "operation": operation,
        "row_id": row_id, "data": data, "client_timestamp": FIXED_TS,
    }


async def sync(client, user_id: str, ops: list[dict], since: int = 0) -> dict:
    body = {"device_id": DEVICES[user_id], "last_server_version": since, "changes": ops}
    r = await client.post("/api/sync", json=body, headers=headers(user_id))
    assert r.status_code == 200, r.text
    return r.json()


async def new_task(client, title: str, assignees: list[str], author: str = ALICE) -> str:
    task_id = str(uuid.uuid4())
    out = await sync(client, author, [op("tasks", "INSERT", task_id, {"title": title, "assigned_to_ids": assignees})])
    assert not out["conflicts"], out
    return task_id


async def delegate(client, task_id: str, recipients: list[str], author: str = ALICE) -> tuple[str, dict]:
    delegation_id = str(uuid.uuid4())
    data = {"task_id": task_id, "recipient_ids": recipients}
    return delegation_id, await sync(client, author, [op("task_delegations", "INSERT", delegation_id, data)])


async def cancel(client, delegation_id: str, user_id: str = ALICE) -> dict:
    return await sync(client, user_id, [op("task_delegations", "DELETE", delegation_id)])


async def complete(client, task_id: str, user_id: str, completed_at: str | None = None) -> dict:
    data = {"task_id": task_id, "completed_by": user_id, "completed_at": completed_at}
    return await sync(client, user_id, [op("task_completions", "INSERT", str(uuid.uuid4()), data)])


async def listed(client, user_id: str) -> dict[str, dict]:
    r = await client.get("/api/delegations", headers=headers(user_id))
    assert r.status_code == 200, r.text
    return {d["task_id"]: d for d in r.json()}


async def seed_household() -> None:
    from app.database import async_session
    from app.models.foyer import Foyer
    from app.models.notification import NotificationToken
    from app.models.user import User
    from app.models.user_foyer import UserFoyer

    async with async_session() as session:
        if await session.get(Foyer, uuid.UUID(HOUSE_ID)) is None:
            session.add(Foyer(id=uuid.UUID(HOUSE_ID), name="Today household"))
            await session.flush()
        for uid, name in NAMES.items():
            if await session.get(User, uuid.UUID(uid)) is None:
                session.add(User(
                    id=uuid.UUID(uid), username=f"today-{name.lower()}", display_name=name,
                    last_active_foyer_id=uuid.UUID(HOUSE_ID),
                ))
                await session.flush()
            found = await session.execute(select(UserFoyer).where(
                UserFoyer.user_id == uuid.UUID(uid), UserFoyer.foyer_id == uuid.UUID(HOUSE_ID),
            ))
            if found.scalar_one_or_none() is None:
                session.add(UserFoyer(user_id=uuid.UUID(uid), foyer_id=uuid.UUID(HOUSE_ID), role="admin"))
            session.add(NotificationToken(user_id=uuid.UUID(uid), fcm_token=TOKENS[uid]))
        await session.commit()
    await reset_state()


async def drop_household_tokens() -> None:
    from sqlalchemy import delete

    from app.database import async_session
    from app.models.notification import NotificationToken

    async with async_session() as session:
        await session.execute(delete(NotificationToken).where(NotificationToken.fcm_token.in_(TOKENS.values())))
        await session.commit()
    await reset_state()


async def reset_state(module_on: bool = True) -> None:
    """End every delegation left by an earlier test, clear the reminder clocks, set the switch."""
    from sqlalchemy import delete

    from app.database import async_session
    from app.models.module_state import ModuleState
    from app.models.task_delegation import TaskDelegation
    from app.models.user_settings import UserSettings
    from app.utils.user_settings import get_or_create_user_settings

    async with async_session() as session:
        await session.execute(
            update(TaskDelegation)
            .where(TaskDelegation.foyer_id == uuid.UUID(HOUSE_ID), TaskDelegation.cancelled_at.is_(None))
            .values(cancelled_at=LEFTOVER_END)
        )
        for uid in NAMES:
            await get_or_create_user_settings(session, uuid.UUID(uid))
        await session.execute(
            update(UserSettings)
            .where(UserSettings.user_id.in_([uuid.UUID(u) for u in NAMES]))
            .values(today_notified_at=None, notif_today_reminders=True)
        )
        await session.execute(delete(ModuleState).where(
            ModuleState.foyer_id == uuid.UUID(HOUSE_ID), ModuleState.module_key == "today",
        ))
        if not module_on:
            session.add(ModuleState(
                foyer_id=uuid.UUID(HOUSE_ID), module_key="today", enabled=False,
                updated_by_user_id=uuid.UUID(ALICE),
            ))
        await session.commit()


def to_household(captured: list) -> list:
    """The captured sends that reach this household's tokens, as (user name, title, body)."""
    by_token = {tok: NAMES[uid] for uid, tok in TOKENS.items()}
    return sorted(
        (by_token[tok], title, body)
        for tokens, title, body in captured for tok in tokens if tok in by_token
    )
