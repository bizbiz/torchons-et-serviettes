"""Does this member see points in this foyer? Server side of the app's
`TokenManager.gamificationVisibleFlow`.

Points show when the foyer uses the `motivation` module AND the member has not
hidden them for themselves (`user_settings.gamification_enabled`, the personal
`hide_for_me` sub-option; no settings row = not hidden). Used where the SERVER
writes text that a member reads, such as the history descriptions: the app can
hide a points field, not a number baked into a sentence.
"""

from __future__ import annotations

import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.user_settings import UserSettings
from app.services.modules import resolve_states


async def points_visible(
    session: AsyncSession, foyer_id: uuid.UUID, user_id: uuid.UUID
) -> bool:
    states = await resolve_states(session, foyer_id, user_id)
    if not states.get("motivation", True):
        return False
    shown = await session.scalar(
        select(UserSettings.gamification_enabled).where(UserSettings.user_id == user_id)
    )
    return shown is not False
