"""A token only works while its user exists and is an ACTIVE member of the token's household.

The JWT has no expiry (docs/appareils.fr.md); `get_current_user` revalidates membership on every
call, and refuses with `DEVICE_REVOKED` — the one 401 on which the app wipes and goes back to the
welcome screen (decided on 2026-10-05).

Paths, and what covers each:
- a member who LEFT the household (`user_foyers.left_at` set), old token on it — one test;
- a DELETED account (`users.deleted_at` set), old token — one test;
- a former member switching BACK into the household left (`POST /foyers/{id}/switch`) — one test;
- a token signed with another secret (what a `JWT_SECRET` rotation leaves on phones) — one test;
- an active member keeps working — the standard, played first in each test.
Not covered: `PUT /foyers/{id}/gamification` and propositions.py, whose membership checks got the
same `left_at` filter; they sit behind `get_current_user`, so a former member's token is refused
before reaching them. The proposition member lists (former members counted) are fixed by reading.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone

import pytest
import pytest_asyncio
from sqlalchemy import select

from app.auth import create_token

LEAVER_ID = "1ea7e400-0000-4000-8000-0000000000a1"
DELETED_ID = "1ea7e400-0000-4000-8000-0000000000a2"
LEFT_FOYER_ID = "1ea7e400-0000-4000-8000-00000000f0a1"
KEPT_FOYER_ID = "1ea7e400-0000-4000-8000-00000000f0a2"
DEVICE = str(uuid.uuid4())


async def _set_state(user_id: str, foyer_id: str, *, left: bool = False, deleted: bool = False):
    """Create or reset the user, the household and the membership to the wanted state."""
    from app.database import async_session
    from app.models.foyer import Foyer
    from app.models.user import User
    from app.models.user_foyer import UserFoyer

    # Any past instant: only the presence of left_at / deleted_at matters, never its value.
    now = datetime(2026, 1, 1, tzinfo=timezone.utc)
    async with async_session() as session:
        if await session.get(Foyer, uuid.UUID(foyer_id)) is None:
            session.add(Foyer(id=uuid.UUID(foyer_id), name=f"Household {foyer_id[-3:]}"))
            await session.flush()
        user = await session.get(User, uuid.UUID(user_id))
        if user is None:
            user = User(id=uuid.UUID(user_id), username=f"u{user_id[-3:]}", display_name="U",
                        last_active_foyer_id=uuid.UUID(foyer_id))
            session.add(user)
            await session.flush()
        user.deleted_at = now if deleted else None
        found = await session.execute(select(UserFoyer).where(
            UserFoyer.user_id == uuid.UUID(user_id), UserFoyer.foyer_id == uuid.UUID(foyer_id),
        ))
        uf = found.scalar_one_or_none()
        if uf is None:
            uf = UserFoyer(user_id=uuid.UUID(user_id), foyer_id=uuid.UUID(foyer_id), role="admin")
            session.add(uf)
        uf.left_at = now if left else None
        await session.commit()


def _headers(user_id: str, foyer_id: str) -> dict:
    return {"Authorization": f"Bearer {create_token(user_id, DEVICE, foyer_id=foyer_id)}"}


@pytest_asyncio.fixture(autouse=True)
async def reset_states():
    await _set_state(LEAVER_ID, LEFT_FOYER_ID)
    await _set_state(LEAVER_ID, KEPT_FOYER_ID)
    await _set_state(DELETED_ID, LEFT_FOYER_ID)
    yield


@pytest.mark.asyncio
async def test_a_member_who_left_is_revoked_on_that_household(client):
    standard = await client.get("/api/tasks", headers=_headers(LEAVER_ID, LEFT_FOYER_ID))
    assert standard.status_code == 200, standard.text

    await _set_state(LEAVER_ID, LEFT_FOYER_ID, left=True)
    r = await client.get("/api/tasks", headers=_headers(LEAVER_ID, LEFT_FOYER_ID))
    assert r.status_code == 401, r.text
    assert r.json()["code"] == "DEVICE_REVOKED", r.text

    kept = await client.get("/api/tasks", headers=_headers(LEAVER_ID, KEPT_FOYER_ID))
    assert kept.status_code == 200, kept.text


@pytest.mark.asyncio
async def test_a_deleted_account_is_revoked(client):
    standard = await client.get("/api/tasks", headers=_headers(DELETED_ID, LEFT_FOYER_ID))
    assert standard.status_code == 200, standard.text

    await _set_state(DELETED_ID, LEFT_FOYER_ID, deleted=True)
    r = await client.get("/api/tasks", headers=_headers(DELETED_ID, LEFT_FOYER_ID))
    assert r.status_code == 401, r.text
    assert r.json()["code"] == "DEVICE_REVOKED", r.text


@pytest.mark.asyncio
async def test_a_former_member_cannot_switch_back_into_the_household(client):
    await _set_state(LEAVER_ID, LEFT_FOYER_ID, left=True)
    r = await client.post(f"/api/foyers/{LEFT_FOYER_ID}/switch",
                          headers=_headers(LEAVER_ID, KEPT_FOYER_ID))
    assert r.status_code == 403, r.text


@pytest.mark.asyncio
async def test_a_token_signed_with_another_secret_is_revoked(client):
    """What a `JWT_SECRET` rotation does to every phone: wipe and welcome, not stuck on 401s."""
    from jose import jwt

    from app.auth import ALGORITHM

    standard = await client.get("/api/tasks", headers=_headers(LEAVER_ID, KEPT_FOYER_ID))
    assert standard.status_code == 200, standard.text

    claims = {"sub": LEAVER_ID, "device_id": DEVICE, "foyer_id": KEPT_FOYER_ID}
    old = jwt.encode(claims, "a-secret-rotated-away", algorithm=ALGORITHM)
    r = await client.get("/api/tasks", headers={"Authorization": f"Bearer {old}"})
    assert r.status_code == 401, r.text
    assert r.json()["code"] == "DEVICE_REVOKED", r.text
