"""The settings row of a user, created on first use without racing a parallel request."""

from __future__ import annotations

from uuid import UUID

from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.user_settings import UserSettings


async def get_or_create_user_settings(session: AsyncSession, user_id: UUID) -> UserSettings:
    """Return the user's settings row, inserting the defaults if it does not exist yet.

    The row is created lazily, on the first read or write. A new account's app sends
    several requests at once: with select-then-insert, two of them both found no row and
    both inserted, and the second one failed on the primary key, a 500 (beta,
    2026-10-12). `ON CONFLICT DO NOTHING` makes the insert a no-op for the loser, which
    then reads the winner's row.
    """
    await session.execute(
        insert(UserSettings).values(user_id=user_id).on_conflict_do_nothing(
            index_elements=[UserSettings.user_id]
        )
    )
    return (
        await session.execute(select(UserSettings).where(UserSettings.user_id == user_id))
    ).scalar_one()
