"""/sync handlers of the `task_completions` table (INSERT, UPDATE, DELETE).

Moved out of app/api/sync.py on 2026-11-18, unchanged but for one addition: a completion
INSERT settles the task's « À faire aujourd'hui » delegation (services/delegations.py).
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.completions import recompute_interval_of_task
from app.api.sync_errors import OutOfScopeError
from app.models.completion import TaskCompletion
from app.models.enums import CompletionReason
from app.models.task import Task
from app.schemas.sync import SyncOperation
from app.services.bonus_malus import (
    apply_multiplier_to_points,
    compute_base_points_for_task,
    compute_bonus_malus_for_completion,
    resolve_stored_reason_and_mult,
)
from app.services.completion_guard import duplicates_recent_completion
from app.services.delegations import settle_task_delegations
from app.utils.errors import LocalizedHTTPException
from app.utils.scoping import assert_active_members, user_foyer_ids, visible_to


async def _insert_completion(
    session: AsyncSession, op: SyncOperation, data: dict, user_id: str,
) -> uuid.UUID | None:
    """/sync INSERT on task_completions (see handle_completion); None for a duplicate."""
    # Charger la tache pour calculer le multiplicateur.
    task_id = uuid.UUID(data["task_id"]) if data.get("task_id") else op.row_id
    # The task must belong to a household the caller is a member of. Not
    # only the token's household: a completion queued offline before a
    # household switch is still legitimate, and is replayed under the new
    # token. A task deleted meanwhile still gets its completion (historical
    # behaviour), as long as it is in one of the caller's households.
    my_foyers = await user_foyer_ids(session, uuid.UUID(user_id))
    owner = (
        await session.execute(select(Task.foyer_id).where(Task.id == task_id, visible_to(Task.id, user_id)))
    ).scalar_one_or_none()
    if owner is None or owner not in my_foyers:
        raise OutOfScopeError("task not found in the caller's households")
    completed_by = uuid.UUID(data.get("completed_by", user_id))
    await assert_active_members(session, owner, [completed_by])
    task_result = await session.execute(
        select(Task).where(Task.id == task_id, Task.deleted_at.is_(None))
    )
    task = task_result.scalar_one_or_none()

    # Date de completion effective (peut etre backdatee par le client).
    if data.get("completed_at"):
        completed_at_eff = datetime.fromisoformat(data["completed_at"])
        if completed_at_eff.tzinfo is None:
            completed_at_eff = completed_at_eff.replace(tzinfo=timezone.utc)
    else:
        completed_at_eff = datetime.now(timezone.utc)

    # Defauts si tache introuvable (edge case: sync d'une completion sur
    # une tache supprimee). On garde le comportement historique.
    points = data.get("points_earned", 1)
    multiplier = 1.0
    reason = CompletionReason.NEUTRAL

    if task is not None and await duplicates_recent_completion(session, task, completed_by, completed_at_eff):
        return None  # acknowledged, not written: services/completion_guard.py
    if task is not None:
        base_points = await compute_base_points_for_task(session, task)
        mult_result = await compute_bonus_malus_for_completion(
            session, task, owner, completed_at_eff,
        )
        reason, multiplier = resolve_stored_reason_and_mult(base_points, mult_result)
        points = apply_multiplier_to_points(base_points, mult_result)

    comp = TaskCompletion(
        id=op.row_id,
        task_id=task_id,
        completed_by=completed_by,
        points_earned=points,
        multiplier=multiplier,
        reason=reason,
        notes=data.get("notes"),
    )
    comp.completed_at = completed_at_eff
    session.add(comp)
    await recompute_interval_of_task(session, task_id)
    await settle_task_delegations(session, task_id, user_id, owner, completed_at_eff)
    return comp.id


async def _delete_completion(
    session: AsyncSession, op: SyncOperation, user_id: str,
) -> uuid.UUID | None:
    """/sync DELETE on task_completions (soft delete)."""
    # Same rule as INSERT: only in a household the caller belongs to.
    my_foyers = await user_foyer_ids(session, uuid.UUID(user_id))
    row = (
        await session.execute(
            select(TaskCompletion)
            .join(Task, Task.id == TaskCompletion.task_id)
            .where(
                TaskCompletion.id == op.row_id,
                TaskCompletion.deleted_at.is_(None),
                Task.foyer_id.in_(my_foyers),
                visible_to(Task.id, user_id),
            )
        )
    ).scalar_one_or_none()
    if row:
        row.deleted_at = datetime.now(timezone.utc)
        await recompute_interval_of_task(session, row.task_id)
    return row.id if row else None


async def _update_completion(
    session: AsyncSession, op: SyncOperation, data: dict, foyer_id: str, user_id: str,
) -> uuid.UUID | None:
    """/sync UPDATE on task_completions (see handle_completion)."""
    foyer_uuid = uuid.UUID(foyer_id) if isinstance(foyer_id, str) else foyer_id
    row = (
        await session.execute(
            select(TaskCompletion)
            .join(Task, Task.id == TaskCompletion.task_id)
            .where(
                TaskCompletion.id == op.row_id,
                TaskCompletion.deleted_at.is_(None),
                Task.foyer_id == foyer_uuid,
                visible_to(Task.id, user_id),
            )
        )
    ).scalar_one_or_none()
    if row:
        if data.get("completed_by"):
            await assert_active_members(session, foyer_uuid, [data["completed_by"]])
            row.completed_by = uuid.UUID(data["completed_by"])
        if data.get("completed_at"):
            ca = datetime.fromisoformat(data["completed_at"])
            if ca.tzinfo is None:
                ca = ca.replace(tzinfo=timezone.utc)
            row.completed_at = ca
        if "notes" in data:
            row.notes = data.get("notes")
        row.version += 1
        if data.get("completed_at"):
            await recompute_interval_of_task(session, row.task_id)
    return row.id if row else None


async def handle_completion(
    session: AsyncSession,
    op: SyncOperation,
    user_id: str,
    foyer_id: str,
) -> uuid.UUID | None:
    """Traite INSERT / UPDATE / DELETE sur task_completions.

    Sur INSERT : applique le systeme bonus/malus (points_earned + multiplier
    + reason) pour rester coherent avec le endpoint REST POST /complete. Le
    payload client n'a que {task_id, completed_by, completed_at, notes} —
    le back recalcule autoritairement.

    Sur UPDATE : applique completed_by / completed_at / notes (edition d'une
    completion offline-first). Memes effets que le REST PUT /completions/{id}
    (completion_edits.py) — pas de recalcul des points, coherent avec ce endpoint.
    INSERT, DELETE et changement de date re-deduisent le rythme d'une tache
    incertaine (completions.recompute_interval_of_task).
    """
    data = op.data or {}

    if op.operation == "INSERT":
        return await _insert_completion(session, op, data, user_id)
    if op.operation == "DELETE":
        return await _delete_completion(session, op, user_id)
    if op.operation == "UPDATE":
        return await _update_completion(session, op, data, foyer_id, user_id)

    raise LocalizedHTTPException(
        status_code=400,
        error_code="SYNC_UNSUPPORTED_OPERATION_FORMAT",
        format_args={"scope": "completions", "operation": op.operation},
    )
