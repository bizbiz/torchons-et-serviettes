"""A member who leaves has 30 days to come back; after that the account is anonymized.

Rule set by Quentin on 2026-11-02 (app/services/member_departure.py), the same for leaving
one's last household and for deleting one's account.

PATHS, enumerated before the cases:
  Coming back (POST /recovery/restore then /restore/confirm):
  (1) left the last household, inside the window ........................................ COVERED
  (2) deleted the account, inside the window ............................................ COVERED
  (3) deleted the account, window over: refused ......................................... COVERED
  Anonymization (the nightly cleanup, anonymize_departed_users):
  (4) deleted more than 30 days ago, and the account HAD ACTED (a completion, a change_log row):
      before 2026-11-02 the purge raised a ForeignKeyViolation here and failed the whole job .. COVERED
  (5) left the last household more than 30 days ago, account not deleted ................ COVERED
  (6) left recently, or still active in another household: untouched ................... COVERED
  Naming former members (GET /auth/users?include_former=true):
  (7) default answer unchanged (pickers, older APKs); opt-in lists former members with
      `left_at` and `anonymized` ........................................................... COVERED
non couvert : un membre parti d'un foyer mais encore actif dans un autre, qui voudrait revenir
dans le premier : la restauration le ramène sur son foyer actif, choix assumé (cas rare).
Every instant is fixed (OLD, far outside the window) or `now` minus one day (inside it), so
the verdict does not depend on the hour the suite runs.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

import pytest
import pytest_asyncio
from sqlalchemy import func, select

from app.auth import create_token
from app.utils import rate_limit
from app.utils.recovery_code import generate_code, hash_code, lookup_key

OLD = datetime(2020, 1, 1, tzinfo=timezone.utc)


def _recent() -> datetime:
    """Inside the 30-day window whatever the day: yesterday."""
    return datetime.now(timezone.utc) - timedelta(days=1)


@pytest.fixture(autouse=True)
def no_rate_limit():
    rate_limit.reset()
    yield
    rate_limit.reset()


@pytest_asyncio.fixture
async def member():
    """A household with A (stays) and B (will leave); B has a code, a solo task, a completion,
    a change_log row, a notification token. Returns the ids and B's recovery code."""
    from app.database import async_session
    from app.models.completion import TaskCompletion
    from app.models.foyer import Foyer
    from app.models.notification import NotificationToken
    from app.models.task import Task
    from app.models.task_assignment import TaskAssignment
    from app.models.user import User
    from app.models.user_foyer import UserFoyer
    from app.utils.changelog import record_change

    ids = {k: uuid.uuid4() for k in ("foyer", "other", "a", "b", "solo", "shared")}
    code = generate_code()
    async with async_session() as session:
        session.add_all([Foyer(id=ids["foyer"], name="Return household"),
                         Foyer(id=ids["other"], name="Other household")])
        await session.flush()
        session.add(User(id=ids["a"], username=f"ra-{ids['a'].hex[:10]}", display_name="A",
                         last_active_foyer_id=ids["foyer"]))
        session.add(User(id=ids["b"], username=f"rb-{ids['b'].hex[:10]}", display_name="Bea",
                         last_active_foyer_id=ids["foyer"], recovery_code_hash=hash_code(code),
                         recovery_lookup=lookup_key(code)))
        await session.flush()
        session.add_all([UserFoyer(user_id=ids["a"], foyer_id=ids["foyer"], role="admin"),
                         UserFoyer(user_id=ids["b"], foyer_id=ids["foyer"], role="member")])
        session.add_all([Task(id=ids["solo"], foyer_id=ids["foyer"], title="b solo"),
                         Task(id=ids["shared"], foyer_id=ids["foyer"], title="shared")])
        await session.flush()
        session.add_all([TaskAssignment(task_id=ids["solo"], user_id=ids["b"]),
                         TaskAssignment(task_id=ids["shared"], user_id=ids["a"]),
                         TaskAssignment(task_id=ids["shared"], user_id=ids["b"])])
        session.add(TaskCompletion(task_id=ids["shared"], completed_by=ids["b"],
                                   completed_at=OLD))
        session.add(NotificationToken(user_id=ids["b"], fcm_token=f"tok-{ids['b'].hex}"))
        await record_change(session, "tasks", ids["shared"], "UPDATE", str(ids["b"]),
                            foyer_id=ids["foyer"], new_data={"title": "shared"})
        await session.commit()
    ids["code"] = code
    return ids


def _headers(user_id, foyer_id) -> dict:
    return {"Authorization": f"Bearer {create_token(str(user_id), 'dev-x', foyer_id=str(foyer_id))}"}


async def _depart(client, ids, how: str, when: datetime | None = None):
    """B leaves (`leave`) or deletes the account (`delete`), then the departure is back-dated."""
    from app.database import async_session
    from app.models.user import User
    from app.models.user_foyer import UserFoyer

    if how == "leave":
        r = await client.post(f"/api/foyers/{ids['foyer']}/leave",
                              headers=_headers(ids["b"], ids["foyer"]))
        assert r.status_code == 200, r.text
    else:
        r = await client.delete("/api/me", headers=_headers(ids["b"], ids["foyer"]))
        assert r.status_code == 204, r.text
    if when is not None:
        async with async_session() as session:
            uf = (await session.execute(select(UserFoyer).where(
                UserFoyer.user_id == ids["b"]))).scalar_one()
            uf.left_at = when
            user = await session.get(User, ids["b"])
            if user.deleted_at is not None:
                user.deleted_at = when
            await session.commit()


async def _restore(client, code: str):
    r = await client.post("/api/recovery/restore", json={"recovery_code": code})
    if r.status_code != 200:
        return r, None
    confirm = await client.post("/api/recovery/restore/confirm",
                                json={"restore_token": r.json()["restore_token"],
                                      "device_id": "dev-back"})
    return r, confirm


async def _b_state(ids):
    from app.database import async_session
    from app.models.task import Task
    from app.models.user import User
    from app.models.user_foyer import UserFoyer

    async with async_session() as session:
        user = await session.get(User, ids["b"])
        uf = (await session.execute(select(UserFoyer).where(
            UserFoyer.user_id == ids["b"], UserFoyer.foyer_id == ids["foyer"]))).scalar_one()
        solo = await session.get(Task, ids["solo"])
        return user, uf, solo


@pytest.mark.asyncio
@pytest.mark.parametrize("how", ["leave", "delete"])
async def test_coming_back_inside_the_window(client, member, how):
    await _depart(client, member, how, when=_recent())
    user, uf, solo = await _b_state(member)
    assert uf.left_at is not None and solo.is_active is False

    preview, confirm = await _restore(client, member["code"])
    assert preview.status_code == 200, preview.text
    assert preview.json()["foyer_name"] == "Return household"
    assert confirm.status_code == 200, confirm.text
    assert confirm.json()["foyer_id"] == str(member["foyer"])

    user, uf, solo = await _b_state(member)
    assert user.deleted_at is None
    assert uf.left_at is None and uf.role == "member"
    assert solo.is_active is True and solo.suspended_at is None
    token = confirm.json()["token"]
    r = await client.get("/api/tasks", headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 200, r.text


@pytest.mark.asyncio
async def test_no_way_back_once_the_window_is_over(client, member):
    await _depart(client, member, "delete", when=OLD)
    preview, _ = await _restore(client, member["code"])
    assert preview.status_code == 404, preview.text


@pytest.mark.asyncio
@pytest.mark.parametrize("how", ["leave", "delete"])
async def test_anonymized_after_the_window_even_if_the_account_acted(client, member, how):
    from app.database import async_session
    from app.models.completion import TaskCompletion
    from app.models.notification import NotificationToken
    from app.services.member_departure import ANONYMOUS_NAME, anonymize_departed_users

    await _depart(client, member, how, when=OLD)
    async with async_session() as session:
        assert await anonymize_departed_users(session) >= 1
        await session.commit()

    user, _, solo = await _b_state(member)
    assert user.anonymized_at is not None and user.display_name == ANONYMOUS_NAME
    assert user.recovery_code_hash is None and user.deleted_at is not None
    assert solo is None
    async with async_session() as session:
        kept = (await session.execute(select(func.count()).select_from(TaskCompletion).where(
            TaskCompletion.completed_by == member["b"]))).scalar_one()
        tokens = (await session.execute(select(func.count()).select_from(NotificationToken).where(
            NotificationToken.user_id == member["b"]))).scalar_one()
    assert kept == 1 and tokens == 0
    preview, _ = await _restore(client, member["code"])
    assert preview.status_code == 404, preview.text


@pytest.mark.asyncio
async def test_recent_or_still_active_elsewhere_is_left_alone(client, member):
    from app.database import async_session
    from app.services.member_departure import anonymize_departed_users

    await _depart(client, member, "leave", when=_recent())
    async with async_session() as session:
        await anonymize_departed_users(session)
        await session.commit()
    assert (await _b_state(member))[0].anonymized_at is None

    # Window over for this household, but B is active in another one.
    await _depart_old_but_active_elsewhere(member)
    async with async_session() as session:
        await anonymize_departed_users(session)
        await session.commit()
    assert (await _b_state(member))[0].anonymized_at is None


async def _depart_old_but_active_elsewhere(ids):
    from app.database import async_session
    from app.models.user_foyer import UserFoyer

    async with async_session() as session:
        uf = (await session.execute(select(UserFoyer).where(
            UserFoyer.user_id == ids["b"], UserFoyer.foyer_id == ids["foyer"]))).scalar_one()
        uf.left_at = OLD
        session.add(UserFoyer(user_id=ids["b"], foyer_id=ids["other"], role="admin"))
        await session.commit()


@pytest.mark.asyncio
async def test_former_members_are_listed_on_request_only(client, member):
    await _depart(client, member, "leave", when=_recent())
    headers = _headers(member["a"], member["foyer"])

    default = await client.get("/api/auth/users", headers=headers)
    assert [u["id"] for u in default.json()] == [str(member["a"])]

    full = (await client.get("/api/auth/users?include_former=true", headers=headers)).json()
    former = [u for u in full if u["id"] == str(member["b"])]
    assert len(former) == 1 and former[0]["display_name"] == "Bea"
    assert former[0]["anonymized"] is False and former[0]["left_at"]
