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
from datetime import datetime
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
        raise await outsider_refusal(session, outside, "USERS_OUTSIDE_FOYER_FORMAT")


async def outsider_refusal(session, outside: set[uuid.UUID], error_code: str):
    """The 400 refusing [outside] users: their NAMES, never their ids. The app shows the message
    as is under « Refusé par le serveur », where ts-test-runner saw on 2026-11-02 « Utilisateurs
    hors du foyer : ['b25bdd0d-…'] ». An id no user carries (forged, deleted account) has no
    name: `<error_code>_UNNAMED` then, a sentence without names.
    """
    from app.models.user import User
    from app.utils.errors import LocalizedHTTPException

    rows = await session.execute(select(User.display_name).where(User.id.in_(list(outside))))
    names = sorted({name for (name,) in rows.all() if name})
    if not names:
        return LocalizedHTTPException(status_code=400, error_code=f"{error_code}_UNNAMED")
    return LocalizedHTTPException(
        status_code=400, error_code=error_code, format_args={"names": ", ".join(names)},
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


# --- Inside the household: a solo task is visible to its assignee only ---------------------
#
# A task that has assignees, none of whom is the user, is invisible to that user: neither its
# title, nor its id, nor its completions. Rule set by Quentin on 2026-10-18 — "une tâche
# attribuée que à Lo n'est visible que par Lo", whoever created it. Such a task earns no points
# (`compute_base_points_for_task`: 0 for one assignee), so hiding it changes no total.
# `scoped()` bounds the HOUSEHOLD only; every route that reads or writes a task, or a row that
# points to one, adds this bound. `tests/test_solo_task_privacy.py` sweeps the GET routes and
# the /sync pull with a second member.


def _user_uuid(current_or_id: dict | str | uuid.UUID) -> uuid.UUID:
    raw = current_or_id["user_id"] if isinstance(current_or_id, dict) else current_or_id
    return raw if isinstance(raw, uuid.UUID) else uuid.UUID(str(raw))


def hidden_task_ids(current_or_id: dict | str | uuid.UUID) -> Select:
    """Ids of the tasks assigned to others only, as a subquery."""
    from app.models.task_assignment import TaskAssignment

    user_id = _user_uuid(current_or_id)
    mine = select(TaskAssignment.task_id).where(TaskAssignment.user_id == user_id)
    return select(TaskAssignment.task_id).where(TaskAssignment.task_id.not_in(mine))


def visible_to(task_id_column: Any, current_or_id: dict | str | uuid.UUID) -> Any:
    """WHERE clause: the task behind `task_id_column` is visible to the user."""
    return task_id_column.not_in(hidden_task_ids(current_or_id))


def visible_tasks(current: dict) -> Select:
    """`scoped(Task, current)` minus the tasks assigned to others only."""
    from app.models.task import Task

    return scoped(Task, current).where(visible_to(Task.id, current))


async def hidden_among(session, current_or_id: dict | str | uuid.UUID, task_ids: set) -> set[str]:
    """Which of `task_ids` are hidden from the user, as strings."""
    from app.models.task_assignment import TaskAssignment

    if not task_ids:
        return set()
    wanted = [i if isinstance(i, uuid.UUID) else uuid.UUID(str(i)) for i in task_ids]
    rows = await session.execute(
        hidden_task_ids(current_or_id).where(TaskAssignment.task_id.in_(wanted)).distinct()
    )
    return {str(r) for r in rows.scalars().all()}


async def without_hidden_tasks(session, current: dict, events: list[dict]) -> list[dict]:
    """History events minus those about a task hidden from the user."""
    task_ids = {e["task"]["id"] for e in events if e.get("task")}
    hidden = await hidden_among(session, current, task_ids)
    return [e for e in events if not (e.get("task") and e["task"]["id"] in hidden)]


# Bounds the refills of one page: a member with hundreds of solo events in a row gets a short
# page rather than an unbounded request.
_HISTORY_REFILLS = 10


async def visible_history(session, current: dict, build, limit: int, before) -> list[dict]:
    """A page of `limit` history events, none about a task hidden from the user.

    `build(n, cursor)` returns the `n` events strictly before `cursor`, newest first, dates in
    ISO. Hidden events are dropped AFTER that cut, so the page is refilled from older events:
    the app reads a short page as the end of the history.
    """
    page: list[dict] = []
    cursor = before
    for _ in range(_HISTORY_REFILLS):
        events = await build(limit, cursor)
        page += await without_hidden_tasks(session, current, events)
        if len(page) >= limit or len(events) < limit:
            break
        cursor = datetime.fromisoformat(events[-1]["date"])
    return page[:limit]
