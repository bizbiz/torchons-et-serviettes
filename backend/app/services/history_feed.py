"""Pieces of the Stats history feed (moved out of api/stats.py, `_build_history`).

Three things live here, each a rule of what the feed SHOWS:
- which task fields a user may read in a « modified » line, and how they are named
  (`task_field_labels`): a key with no label is never printed raw;
- the task title AS IT WAS when an event happened (`TaskTitleIndex`), not the current one;
- the vote events (`vote_events`): one per member and task, the first vote included.
"""

from __future__ import annotations

from datetime import datetime
from typing import Callable
from uuid import UUID

from sqlalchemy import or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.sync import ChangeLog
from app.models.task import Task
from app.models.task_affinity import TaskAffinity
from app.models.task_effort import TaskEffort
from app.services.history_text import _t

# Champs "techniques" ignores dans les UPDATE tasks : leur presence seule
# ne doit pas generer un event d'historique.
TASK_TECHNICAL_FIELDS = {
    "updated_at",
    "version",
    "last_completed_at",
    "next_due_at",
}

# Libellés des champs métier pour les descriptions, par locale. Every key the server writes
# into a task's change_log row must be here or in TASK_TECHNICAL_FIELDS
# (tests/test_history_feed.py checks the REST update schema and the /sync whitelist).
# `room_id` and `category_id` are no longer written (rooms and categories became contexts), but
# the rows already in the log still carry them.
TASK_FIELD_LABELS_BY_LOCALE: dict[str, dict[str, str]] = {
    "fr": {
        "title": "titre",
        "description": "description",
        "category_id": "catégorie",
        "room_id": "pièce",
        "context_ids": "contextes",
        "assigned_to_ids": "attribution",
        "points_value": "points",
        "weight": "poids",
        "recurrence_rule": "récurrence",
        "tolerance": "tolérance",
        "is_active": "statut",
        "is_archived": "archivage",
        "sort_order": "ordre",
        "deleted_at": "suppression",
    },
    "en": {
        "title": "title",
        "description": "description",
        "category_id": "category",
        "room_id": "room",
        "context_ids": "contexts",
        "assigned_to_ids": "assignment",
        "points_value": "points",
        "weight": "weight",
        "recurrence_rule": "recurrence",
        "tolerance": "tolerance",
        "is_active": "status",
        "is_archived": "archiving",
        "sort_order": "order",
        "deleted_at": "deletion",
    },
}


def task_field_labels(fields: list[str], locale: str) -> list[tuple[str, str]]:
    """The `(key, label)` of the fields worth showing; technical and unnamed keys are dropped.

    A key with no label is dropped, not printed: /sync journals the client's payload as it
    came, so a key the server does not know (`context_ids` before it had a label) used to
    reach the feed as a raw column name. A line whose every key is dropped is not shown at
    all: a generic word ("other") would only say that something changed without saying what.
    """
    labels = TASK_FIELD_LABELS_BY_LOCALE.get(locale) or TASK_FIELD_LABELS_BY_LOCALE["fr"]
    return [(f, labels[f]) for f in fields if f not in TASK_TECHNICAL_FIELDS and f in labels]


class TaskTitleIndex:
    """The title a task carried at a given moment, from change_log, else its current one.

    The feed used to print the CURRENT title on every line of a task, so a renamed task
    rewrote its own past (« a créé la tâche 'Sortir les poubelles' » for a task created as
    'Poubelles'). For one event: the `title` of the row itself when it carries one, else the
    latest earlier `title` journalled for that task, else the current title. A task that no
    longer exists keeps the last title known, suffixed « (supprimée) ».
    """

    def __init__(
        self,
        current: dict[UUID, str],
        history: dict[UUID, list[tuple[datetime, str]]],
        deleted_suffix: str,
    ):
        self._current = current
        self._history = history
        self._suffix = deleted_suffix

    @classmethod
    async def load(
        cls, session: AsyncSession, foyer_uuid: UUID, task_ids: set[UUID], locale: str,
    ) -> "TaskTitleIndex":
        current: dict[UUID, str] = {}
        history: dict[UUID, list[tuple[datetime, str]]] = {}
        if task_ids:
            # Scoped to the household: a change_log row can name a task id that is not ours
            # (an op sent by a client on someone else's id is still journalled), and the
            # title must not leak through it.
            rows = await session.execute(
                select(Task.id, Task.title).where(
                    Task.id.in_(task_ids), Task.foyer_id == foyer_uuid,
                )
            )
            current = {row[0]: row[1] for row in rows.all()}
            logged = await session.execute(
                select(ChangeLog.row_id, ChangeLog.changed_at, ChangeLog.new_data["title"].astext)
                .where(
                    ChangeLog.table_name == "tasks",
                    ChangeLog.row_id.in_(task_ids),
                    ChangeLog.foyer_id == foyer_uuid,
                    ChangeLog.new_data["title"].astext.isnot(None),
                )
                .order_by(ChangeLog.changed_at)
            )
            for row_id, changed_at, title in logged.all():
                if title:
                    history.setdefault(row_id, []).append((changed_at, title))
        return cls(current, history, _t(locale, "task_deleted_suffix"))

    def at(self, task_id: UUID, when: datetime, own_title: str | None = None) -> str:
        """The title at `when`; `own_title` is the `title` carried by the event's own row."""
        gone = task_id not in self._current
        title = own_title
        if title is None:
            earlier = [t for changed_at, t in self._history.get(task_id, []) if changed_at <= when]
            if earlier:
                title = earlier[-1]
            elif not gone:
                title = self._current[task_id]
            elif task_id in self._history:
                # Hard-deleted and nothing journalled that early: the last title known.
                title = self._history[task_id][-1][1]
        if title is None:
            return "?"
        return f"{title}{self._suffix}" if gone else title


async def _vote_rows(
    session: AsyncSession, foyer_uuid: UUID, model, *, before: datetime | None,
    since: datetime | None, actor_id: UUID | None, q: str | None, fetch_limit: int,
) -> list:
    """The latest votes of `model` (TaskAffinity or TaskEffort) in the household."""
    query = select(model).join(Task, Task.id == model.task_id).where(Task.foyer_id == foyer_uuid)
    if before is not None:
        query = query.where(model.updated_at < before)
    if since is not None:
        query = query.where(model.updated_at >= since)
    if actor_id is not None:
        query = query.where(model.user_id == actor_id)
    if q is not None:
        pattern = f"%{q}%"
        renamed = select(ChangeLog.row_id).where(
            ChangeLog.table_name == "tasks", ChangeLog.foyer_id == foyer_uuid,
            ChangeLog.new_data["title"].astext.ilike(pattern),
        )
        query = query.where(or_(Task.title.ilike(pattern), Task.id.in_(renamed)))
    return list((await session.execute(
        query.order_by(model.updated_at.desc()).limit(fetch_limit)
    )).scalars().all())


async def vote_events(
    session: AsyncSession,
    foyer_uuid: UUID,
    *,
    kind: str,
    before: datetime | None,
    since: datetime | None,
    actor_id: UUID | None,
    q: str | None,
    fetch_limit: int,
    locale: str,
    actor_of: Callable[[UUID | None], dict | None],
    minutes_text: Callable[[int], str],
) -> list[dict]:
    """The `affinity` or `effort` events of the household, newest first, at most `fetch_limit`.

    One event per member and task, dated by the vote's last touch, FIRST VOTES INCLUDED. A
    filter `updated_at > created_at` used to hide them (it kept only votes that had changed
    once), so a task rated once never appeared. Nothing creates a row but an explicit vote,
    so none of these is a default row; and a row is one line, so a replayed vote adds none.

    `q` matches the title the line prints (the title at the time): the SQL filter is widened
    to the titles the task carried, then the printed title is checked here.
    """
    model = TaskAffinity if kind == "affinity" else TaskEffort
    votes = await _vote_rows(
        session, foyer_uuid, model, before=before, since=since, actor_id=actor_id, q=q,
        fetch_limit=fetch_limit,
    )
    titles = await TaskTitleIndex.load(session, foyer_uuid, {v.task_id for v in votes}, locale)
    events = []
    for vote in votes:
        title = titles.at(vote.task_id, vote.updated_at)
        if q is not None and q.lower() not in title.lower():
            continue
        actor = actor_of(vote.user_id)
        who = actor["name"] if actor else "?"
        if kind == "affinity":
            description = _t(locale, "affinity", who=who, title=title, score=vote.score)
            meta = {"score": vote.score}
        else:
            description = _t(
                locale, "effort", who=who, title=title, minutes=minutes_text(vote.points)
            )
            meta = {"points": vote.points, "minutes": vote.points * 5}
        events.append({
            "type": kind,
            "date": vote.updated_at,
            "description": description,
            "user": who,
            "actor": actor,
            "task": {"id": str(vote.task_id), "title": title},
            "meta": meta,
        })
    return events
