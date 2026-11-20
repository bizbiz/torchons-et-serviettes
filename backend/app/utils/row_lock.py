"""Serialise a "read the row, then write it" step across concurrent transactions."""

from __future__ import annotations

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession


async def lock_key(session: AsyncSession, key: str) -> None:
    """Wait for any other transaction holding `key`, then hold it until this one ends.

    A Postgres transaction-level advisory lock, released on commit or rollback. Take it
    right BEFORE the select of a select-then-insert/update on a logical row (a vote, a
    toggle, a day), on every path that writes that row. The app's sync worker can resend a
    request that is still in flight, so two copies of the same write meet in two
    transactions: without the lock both find no row and both insert, and the second fails on
    the primary key, a 500 (beta, 2026-11-20, PUT /api/tasks/{id}/effort). With it the second
    waits, then (READ COMMITTED: each statement sees what was committed meanwhile) finds the
    first one's row and updates it.

    The key is hashed by Postgres to a bigint; a hash collision only makes two unrelated keys
    wait for each other, it never skips a check. Prefix keys by kind (`vote:effort:...`).
    """
    await session.execute(
        text("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))"),
        {"key": key},
    )


# Keys shared by several writers of one row: spelled once, so they cannot drift apart.


def module_state_key(foyer_id, module_key: str, row_user_id) -> str:
    """One module_states row: collective when `row_user_id` is None, else that member's."""
    return f"module:{foyer_id}:{module_key}:{row_user_id}"


def presence_key(foyer_id) -> str:
    """The presence_days of one foyer (PUT /presence, check-in, prompt answer)."""
    return f"presence:{foyer_id}"
