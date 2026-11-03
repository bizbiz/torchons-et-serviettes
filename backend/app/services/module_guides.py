"""Module guide pages: who has seen which, and which one is due.

A guide is shown once per member. "Seen" lives in `module_guide_views`, keyed by
user, so it holds across devices and reinstalls.

"Due" covers one case only: a COLLECTIVE module that ANOTHER member switched on.
That member saw the guide when they enabled it; everyone else in the household
has not, and meets the module already running. The rows that do not count:
- a default-on module with no state row (nobody decided anything, nobody to
  explain it to),
- a row written by the migration backfill or by the member themselves
  (`updated_by_user_id` NULL or equal to the member).
"""

from __future__ import annotations

import uuid

from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.module_guide_view import ModuleGuideView
from app.models.module_state import ModuleState


async def seen_module_keys(session: AsyncSession, user_id: uuid.UUID) -> set[str]:
    result = await session.execute(
        select(ModuleGuideView.module_key).where(ModuleGuideView.user_id == user_id)
    )
    return set(result.scalars().all())


async def due_module_keys(
    session: AsyncSession,
    foyer_id: uuid.UUID,
    user_id: uuid.UUID,
    states: dict[str, bool],
    seen: set[str],
) -> set[str]:
    """Keys of the modules whose guide must be shown at this member's next open.

    `states` is the member's effective enablement (`resolve_states`), `seen` the
    result of `seen_module_keys`; both are passed in because the caller already
    holds them.
    """
    result = await session.execute(
        select(ModuleState.module_key, ModuleState.updated_by_user_id).where(
            ModuleState.foyer_id == foyer_id,
            ModuleState.user_id.is_(None),
        )
    )
    return {
        key
        for key, updated_by in result.all()
        if states.get(key, False)
        and updated_by is not None
        and updated_by != user_id
        and key not in seen
    }


async def mark_guide_seen(
    session: AsyncSession, user_id: uuid.UUID, module_key: str
) -> None:
    """Record that this member saw the guide. Idempotent; does not commit."""
    await session.execute(
        pg_insert(ModuleGuideView)
        .values(user_id=user_id, module_key=module_key)
        .on_conflict_do_nothing(index_elements=["user_id", "module_key"])
    )
