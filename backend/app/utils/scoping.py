"""Helpers pour scoper les queries SQLAlchemy par foyer_id.

Usage :
    stmt = scoped(Task, current).where(Task.is_active.is_(True))
    result = await session.execute(stmt)

Scope = garantit WHERE Task.foyer_id = current["foyer_id"]. Utiliser
systematiquement sur les tables top-level (tasks, categories, rooms,
streaks, badges, fair_share_config, change_log) — c'est la barriere
d'isolation entre foyers.

Pour les tables dependantes (task_assignments, task_completions, etc.),
le scoping est transitif : on joint sur tasks et on filtre via Task.foyer_id.
"""

from __future__ import annotations

import uuid
from typing import Any

from sqlalchemy import Select, select


def scoped(model: Any, current: dict) -> Select:
    """Retourne un SELECT deja filtre sur model.foyer_id = current foyer.

    Le modele DOIT avoir une colonne foyer_id. Sinon, ValueError.
    """
    if not hasattr(model, "foyer_id"):
        raise ValueError(f"{model.__name__} n'a pas de colonne foyer_id — utilise un JOIN via tasks")
    foyer_id = current["foyer_id"]
    foyer_uuid = uuid.UUID(foyer_id) if isinstance(foyer_id, str) else foyer_id
    return select(model).where(model.foyer_id == foyer_uuid)


def current_foyer_uuid(current: dict) -> uuid.UUID:
    """Convertit le foyer_id du current user en UUID (pour les INSERT)."""
    foyer_id = current["foyer_id"]
    return uuid.UUID(foyer_id) if isinstance(foyer_id, str) else foyer_id


async def active_member_ids(session, foyer_uuid: uuid.UUID) -> set[uuid.UUID]:
    """Return the ids of the users currently in the household (left_at IS NULL)."""
    from app.models.user_foyer import UserFoyer

    rows = await session.execute(
        select(UserFoyer.user_id).where(
            UserFoyer.foyer_id == foyer_uuid,
            UserFoyer.left_at.is_(None),
        )
    )
    return {row[0] for row in rows.all()}


async def assert_active_members(session, foyer_uuid: uuid.UUID, user_ids) -> None:
    """Refuse any user id that is not a current member of the household.

    Every route that stores a user id sent by the client (assignees, the
    author of a completion, a fair-share target) must go through here:
    otherwise a caller writes another household's members into its own rows,
    or its own members into another household's.
    """
    from app.utils.errors import LocalizedHTTPException

    wanted = {uuid.UUID(str(u)) for u in user_ids}
    if not wanted:
        return
    outside = wanted - await active_member_ids(session, foyer_uuid)
    if outside:
        raise LocalizedHTTPException(
            status_code=400,
            error_code="USERS_OUTSIDE_FOYER_FORMAT",
            format_args={"invalid": sorted(str(u) for u in outside)},
        )


async def user_foyer_ids(session, user_uuid: uuid.UUID) -> set[uuid.UUID]:
    """Return the households the user currently belongs to (left_at IS NULL)."""
    from app.models.user_foyer import UserFoyer

    rows = await session.execute(
        select(UserFoyer.foyer_id).where(
            UserFoyer.user_id == user_uuid,
            UserFoyer.left_at.is_(None),
        )
    )
    return {row[0] for row in rows.all()}
