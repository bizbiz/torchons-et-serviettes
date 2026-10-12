"""Restore finds an account by the key of its code, not by an argon2 verify per account.

Paths, and what covers each:
- a KEYED account (code issued after 2026-10-12) — found with one verify, no scan;
- an UNKEYED account (code issued before) — found by the scan, then keyed on the spot;
- a key made with an OLD secret (after a JWT_SECRET rotation) — scanned like an unkeyed one,
  re-keyed, never lost;
- a WRONG code — the scan never includes accounts keyed with the current secret.
Each test counts the accounts handed to `_match_code` (the argon2 loop), which is what the
change is about; the restore answer alone would be the same before and after.
Not covered: the key written at onboarding, pairing and regeneration, three one-line writes
next to the hash they accompany.
"""

from __future__ import annotations

import uuid

import pytest

from app.api import recovery
from app.utils import rate_limit
from app.utils.recovery_code import generate_code, hash_code, lookup_key


async def _account(code: str, lookup: str | None) -> str:
    from app.database import async_session
    from app.models.user import User

    user_id = uuid.uuid4()
    async with async_session() as session:
        session.add(User(id=user_id, username=f"rk{user_id.hex[:6]}", display_name="RK",
                         recovery_code_hash=hash_code(code), recovery_lookup=lookup))
        await session.commit()
    return str(user_id)


async def _lookup_of(user_id: str) -> str | None:
    from app.database import async_session
    from app.models.user import User

    async with async_session() as session:
        return (await session.get(User, uuid.UUID(user_id))).recovery_lookup


@pytest.fixture
def scanned(monkeypatch):
    """Sizes of the account lists given to the argon2 loop, call by call."""
    sizes: list[list[str]] = []
    real = recovery._match_code

    def spy(code, users):
        sizes.append([str(u.id) for u in users])
        return real(code, users)

    monkeypatch.setattr(recovery, "_match_code", spy)
    rate_limit.reset()
    yield sizes
    rate_limit.reset()


@pytest.mark.asyncio
async def test_a_keyed_account_is_found_with_one_verify(client, scanned):
    code = generate_code()
    user_id = await _account(code, lookup_key(code))
    r = await client.post("/api/recovery/restore", json={"recovery_code": code})
    assert r.status_code == 200, r.text
    assert scanned == [[user_id]], scanned


@pytest.mark.asyncio
async def test_an_unkeyed_account_is_found_then_keyed(client, scanned):
    code = generate_code()
    user_id = await _account(code, None)
    r = await client.post("/api/recovery/restore", json={"recovery_code": code})
    assert r.status_code == 200, r.text
    assert len(scanned) == 2 and scanned[0] == [] and user_id in scanned[1], scanned
    assert await _lookup_of(user_id) == lookup_key(code)


@pytest.mark.asyncio
async def test_a_key_from_an_old_secret_is_rescanned_and_rekeyed(client, scanned):
    code = generate_code()
    user_id = await _account(code, "deadbeef:" + "0" * 40)
    r = await client.post("/api/recovery/restore", json={"recovery_code": code})
    assert r.status_code == 200, r.text
    assert user_id in scanned[-1], scanned
    assert await _lookup_of(user_id) == lookup_key(code)


@pytest.mark.asyncio
async def test_a_wrong_code_never_scans_keyed_accounts(client, scanned):
    code = generate_code()
    keyed_id = await _account(code, lookup_key(code))
    r = await client.post("/api/recovery/restore", json={"recovery_code": "WXYZ2345"})
    assert r.status_code == 404, r.text
    assert all(keyed_id not in batch for batch in scanned), scanned
