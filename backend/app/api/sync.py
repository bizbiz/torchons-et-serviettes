"""Endpoint POST /api/sync — synchronisation offline bidirectionnelle."""

from __future__ import annotations

import json
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends
from pydantic import ValidationError
from sqlalchemy import delete, func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.utils.errors import LocalizedHTTPException
from app.models.completion import TaskCompletion
from app.models.sync import ChangeLog, ProcessedOp
from app.models.task import Task
from app.models.task_assignment import TaskAssignment
from app.schemas.sync import (
    ChangeLogEntry,
    SyncAppliedOp,
    SyncConflict,
    SyncOperation,
    SyncRequest,
    SyncResponse,
)
from app.api.sync_completions import handle_completion
from app.api.sync_delegations import handle_delegation
from app.api.sync_errors import OutOfScopeError, StaleUpdateError
from app.api.tasks import replace_task_contexts
from app.services.push import send_sync_completions
from app.services.recurrence import checked_recurrence_rule
from app.utils.changelog import record_change
from app.utils.scoping import assert_active_members, hidden_among, user_foyer_ids, visible_to
from app.services.delegations import DELEGATION_REFUSALS
from app.utils.user_settings import get_or_create_user_settings
from app.schemas.task import TaskUpdate

router = APIRouter(prefix="/api", tags=["sync"])


async def _op_foyer(session: AsyncSession, op: SyncOperation, user_id: str, token_foyer: str) -> str:
    """Household an op applies to: the one it was queued in, if the caller still belongs to it."""
    if op.foyer_id is None:
        return token_foyer
    if op.foyer_id not in await user_foyer_ids(session, uuid.UUID(user_id)):
        raise OutOfScopeError(f"not a member of household {op.foyer_id}")
    return str(op.foyer_id)


# Refusals no replay can lift: the op names someone outside the household, or
# its values break the task bounds. The client drops a `rejected` op and says so.
# Everything else stays `op_error`, replayed: an unknown table, operation or
# recurrence type is a server OLDER than the app (docs/architecture.md § Sync ordering),
# and passes once the server is deployed -- dropping it would lose the op.
DEFINITIVE_REFUSALS = frozenset({
    "USERS_OUTSIDE_FOYER_FORMAT", "USERS_OUTSIDE_FOYER_FORMAT_UNNAMED", *DELEGATION_REFUSALS,
})


def _is_definitive_refusal(e: Exception) -> bool:
    if isinstance(e, ValidationError):
        return True
    return isinstance(e, LocalizedHTTPException) and e.error_code in DEFINITIVE_REFUSALS


def _conflict_for(op: SyncOperation, e: Exception) -> SyncConflict:
    """The conflict reported for an op whose handler raised `e`."""
    base = {"client_op_id": op.client_op_id, "table": op.table, "row_id": op.row_id}
    if isinstance(e, OutOfScopeError):
        return SyncConflict(**base, reason="out_of_scope")
    if isinstance(e, StaleUpdateError):
        return SyncConflict(**base, server_data=e.server_data, reason="stale_update")
    if isinstance(e, IntegrityError):
        return SyncConflict(**base, reason="unique_violation")
    # Our own validation errors keep their message (it names the rule broken);
    # anything else -- driver, database -- gives its class only, its text can
    # carry SQL and data.
    detail = (
        f"{type(e).__name__}: {e}"
        if isinstance(e, (ValueError, LocalizedHTTPException))
        else type(e).__name__
    )
    reason = "rejected" if _is_definitive_refusal(e) else "op_error"
    return SyncConflict(**base, reason=reason, detail=detail)


async def _visible_changes(session: AsyncSession, user_id, rows: list) -> list:
    """The pulled changes minus those about a task hidden from the user (solo task of another
    member, app/utils/scoping.py): the task's own rows, and every row that points to it.

    A completion UPDATE or DELETE may carry no `task_id`: its task is read from the completion.
    """
    def task_of(row):
        if row.table_name == "tasks":
            return str(row.row_id)
        data = row.new_data or row.old_data or {}
        return str(data["task_id"]) if isinstance(data, dict) and data.get("task_id") else None

    orphan_completions = [
        r.row_id for r in rows if r.table_name == "task_completions" and task_of(r) is None
    ]
    completion_task = {}
    if orphan_completions:
        found = await session.execute(
            select(TaskCompletion.id, TaskCompletion.task_id).where(TaskCompletion.id.in_(orphan_completions))
        )
        completion_task = {str(cid): str(tid) for cid, tid in found.all()}

    def resolved(row):
        return task_of(row) or completion_task.get(str(row.row_id))

    hidden = await hidden_among(session, user_id, {t for t in map(resolved, rows) if t})
    return [r for r in rows if resolved(r) not in hidden]


@router.post("/sync", response_model=SyncResponse)
async def sync(
    body: SyncRequest,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Synchronise les operations offline du client et retourne les changements serveur (scope foyer)."""
    user_id = current["user_id"]
    foyer_id = current["foyer_id"]
    applied: list[SyncAppliedOp] = []
    conflicts: list[SyncConflict] = []
    inserted_completions: list[uuid.UUID] = []  # notified once committed (services/push.py)

    for op in body.changes:
        # Idempotence : si deja traite, retourner le resultat precedent
        existing = (
            await session.execute(
                select(ProcessedOp).where(ProcessedOp.client_op_id == op.client_op_id)
            )
        ).scalar_one_or_none()

        if existing is not None:
            applied.append(SyncAppliedOp(
                client_op_id=op.client_op_id,
                server_row_id=existing.server_row_id,
                new_version=0,
            ))
            continue

        try:
            # One SAVEPOINT per op: a failing op rolls back ITSELF only. Until
            # 2026-09-30 the handlers below called `session.rollback()`, which
            # also undid every op applied earlier in the same batch — ops
            # already reported `applied`, which the client then drops from its
            # queue: silent data loss.
            async with session.begin_nested():
                op_foyer = await _op_foyer(session, op, user_id, foyer_id)
                result = await _apply_op(session, op, user_id, op_foyer, body.device_id)
            applied.append(result)
            if op.table == "task_completions" and op.operation == "INSERT":
                inserted_completions.append(result.server_row_id)
        except Exception as e:
            # Filet de securite : toute erreur sur UNE op devient un conflit
            # LOCALISE a cette op au lieu de faire remonter l'exception hors du
            # endpoint -> sinon le batch /sync entier renvoie 400, ne commit
            # jamais, et la file offline du foyer reste bloquee a l'infini.
            # Voir docs/cache-conventions.md + commit 08d4a2c (deploy ordering).
            conflicts.append(_conflict_for(op, e))

    await session.commit()
    await send_sync_completions(session, inserted_completions, user_id)

    # Pull : changements serveur depuis last_server_version,
    # en excluant les ops originant de ce device. SCOPE FOYER : un client ne
    # recoit que les changements de son foyer actuel.
    own_op_ids = select(ProcessedOp.client_op_id).where(
        ProcessedOp.device_id == body.device_id
    ).scalar_subquery()

    foyer_uuid = uuid.UUID(foyer_id) if isinstance(foyer_id, str) else foyer_id
    server_rows = (
        await session.execute(
            select(ChangeLog)
            .where(ChangeLog.server_version > body.last_server_version)
            .where(ChangeLog.foyer_id == foyer_uuid)
            .where(
                (ChangeLog.client_op_id.is_(None))
                | (~ChangeLog.client_op_id.in_(own_op_ids))
            )
            .order_by(ChangeLog.server_version)
            .limit(500)
        )
    ).scalars().all()
    server_rows = await _visible_changes(session, user_id, server_rows)

    max_version_row = await session.execute(
        select(func.max(ChangeLog.server_version))
    )
    max_version = max_version_row.scalar() or body.last_server_version

    server_changes = [
        ChangeLogEntry(
            server_version=r.server_version,
            table=r.table_name,
            row_id=r.row_id,
            operation=r.operation,
            data=r.new_data,
            changed_at=r.changed_at,
        )
        for r in server_rows
    ]

    return SyncResponse(
        server_version=max_version,
        applied=applied,
        conflicts=conflicts,
        server_changes=server_changes,
    )


async def _apply_op(
    session: AsyncSession,
    op: SyncOperation,
    user_id: str,
    foyer_id: str,
    device_id: uuid.UUID,
) -> SyncAppliedOp:
    """Dispatch et execute une operation sync (scope foyer)."""
    handlers = {
        "tasks": _handle_task,
        "task_completions": handle_completion,
        "task_delegations": handle_delegation,
        "user_settings": _handle_settings,
    }
    handler = handlers.get(op.table)
    if handler is None:
        raise LocalizedHTTPException(
            status_code=400,
            error_code="SYNC_UNKNOWN_TABLE_FORMAT",
            format_args={"table": op.table},
        )

    written_id = await handler(session, op, user_id, foyer_id)
    # None: nothing was written (row gone, or not visible to the caller). The op is
    # acknowledged, but journalled nowhere: a change_log row would show the owner an
    # edit that never happened, and send it to every device.
    server_row_id = written_id or op.row_id

    # Enregistrer dans processed_ops
    session.add(ProcessedOp(
        client_op_id=op.client_op_id,
        table_name=op.table,
        server_row_id=server_row_id,
        device_id=device_id,
    ))

    # Enregistrer dans change_log (avec foyer_id)
    if written_id is not None:
        await record_change(
            session, op.table, server_row_id, op.operation, user_id,
            foyer_id=foyer_id,
            new_data=op.data,
            client_op_id=op.client_op_id,
        )

    await session.flush()

    # Recuperer le server_version du dernier change_log insere
    last_version = (
        await session.execute(select(func.max(ChangeLog.server_version)))
    ).scalar() or 0

    return SyncAppliedOp(
        client_op_id=op.client_op_id,
        server_row_id=server_row_id,
        new_version=last_version,
    )


# Keys a client may write on a task through /sync. Mirrors the payload built by
# the app's TaskRepository.updateTask; anything else is dropped.
_TASK_UPDATE_FIELDS = frozenset({
    "title", "description", "weight", "tolerance", "is_active",
    "recurrence_rule", "assigned_to_ids", "context_ids",
})


def _truncate_to_ms(dt: datetime) -> datetime:
    """Tronque un datetime a la milliseconde (resolution Android)."""
    return dt.replace(microsecond=(dt.microsecond // 1000) * 1000)


async def _writable_task(session: AsyncSession, task_id, foyer_uuid: uuid.UUID, user_id: str):
    """The live task of this household the caller may see, or None."""
    return (
        await session.execute(
            select(Task).where(
                Task.id == task_id,
                Task.foyer_id == foyer_uuid,
                Task.deleted_at.is_(None),
                visible_to(Task.id, user_id),
            )
        )
    ).scalar_one_or_none()


async def _handle_task(
    session: AsyncSession,
    op: SyncOperation,
    user_id: str,
    foyer_id: str,
) -> uuid.UUID | None:
    """Traite INSERT / UPDATE / DELETE sur tasks."""
    data = op.data or {}
    foyer_uuid = uuid.UUID(foyer_id) if isinstance(foyer_id, str) else foyer_id

    if op.operation == "INSERT":
        # Same bounds as the REST route (weight 1..18, tolerance enum, title length).
        TaskUpdate.model_validate({k: data[k] for k in ("title", "weight", "tolerance") if k in data})
        await assert_active_members(session, foyer_uuid, data.get("assigned_to_ids", []))
        task = Task(
            id=op.row_id,
            foyer_id=foyer_uuid,
            title=data.get("title", "Sans titre"),
            description=data.get("description"),
            recurrence_rule=checked_recurrence_rule(data.get("recurrence_rule")),
            weight=data.get("weight", 1),
            tolerance=data.get("tolerance", "NORMAL"),
        )
        session.add(task)
        await session.flush()
        for uid in data.get("assigned_to_ids", []):
            session.add(TaskAssignment(task_id=task.id, user_id=uuid.UUID(uid)))
        if data.get("context_ids") is not None:
            await replace_task_contexts(
                session, task, [uuid.UUID(c) for c in data["context_ids"]]
            )
        return task.id

    if op.operation == "UPDATE":
        row = await _writable_task(session, op.row_id, foyer_uuid, user_id)
        if not row:
            # Gone, never in this household, or assigned to others only: no replay will apply it.
            raise OutOfScopeError("task not found in this household")
        if _truncate_to_ms(row.updated_at) > _truncate_to_ms(op.client_timestamp):
            raise StaleUpdateError(_row_to_dict(row))
        # Only the fields the app sends (TaskRepository.updateTask) are written.
        # The loop used to `setattr` any key that matched a column, so a client
        # could move its task into another household (`foyer_id`), set the
        # points outside 1..18 (`weight`), or rewrite `version`, `is_archived`,
        # `template_key`... Unknown keys are dropped, not refused: a refused op
        # would stay in the client's retry queue forever.
        data = {k: v for k, v in data.items() if k in _TASK_UPDATE_FIELDS}
        TaskUpdate.model_validate(
            {k: data[k] for k in ("title", "weight", "tolerance", "is_active") if k in data}
        )
        for key, val in data.items():
            if key == "assigned_to_ids":
                await assert_active_members(session, foyer_uuid, val or [])
                await session.execute(
                    delete(TaskAssignment).where(TaskAssignment.task_id == op.row_id)
                )
                for uid in val:
                    session.add(TaskAssignment(task_id=op.row_id, user_id=uuid.UUID(uid)))
            elif key == "context_ids":
                # Pas une colonne de Task : sans cette branche, le `hasattr`
                # plus bas la laisserait tomber en silence et une edition faite
                # hors ligne perdrait ses rattachements a la reconnexion.
                await replace_task_contexts(
                    session, row, [uuid.UUID(c) for c in (val or [])]
                )
            elif key == "recurrence_rule":
                setattr(row, key, checked_recurrence_rule(val))
            else:
                setattr(row, key, val)
        row.version += 1
        return row.id

    if op.operation == "DELETE":
        row = await _writable_task(session, op.row_id, foyer_uuid, user_id)
        if row:
            row.deleted_at = datetime.now(timezone.utc)
            row.version += 1
        return row.id if row else None

    raise LocalizedHTTPException(
        status_code=400,
        error_code="SYNC_UNKNOWN_OPERATION_FORMAT",
        format_args={"operation": op.operation},
    )


async def _handle_settings(
    session: AsyncSession,
    op: SyncOperation,
    user_id: str,
    foyer_id: str,
) -> uuid.UUID:
    """Traite UPDATE sur user_settings."""
    data = op.data or {}
    uid = uuid.UUID(user_id)

    if op.operation == "UPDATE":
        row = await get_or_create_user_settings(session, uid)
        for key, val in data.items():
            # Server-owned: moved only by POST /api/rating-prompt/outcome, and by the
            # « À faire aujourd'hui » notifications (services/delegation_push.py).
            if key in ("rating_prompt_gap_days", "rating_prompt_next_at", "today_notified_at"):
                continue
            if hasattr(row, key) and key != "user_id":
                setattr(row, key, val)
        return uid

    raise LocalizedHTTPException(
        status_code=400,
        error_code="SYNC_UNSUPPORTED_OPERATION_FORMAT",
        format_args={"scope": "settings", "operation": op.operation},
    )


def _row_to_dict(row) -> dict:
    """Serialise une row SQLAlchemy en dict pour les conflits."""
    result = {}
    for c in row.__table__.columns:
        val = getattr(row, c.key, None)
        if isinstance(val, (uuid.UUID, datetime)):
            val = str(val)
        result[c.key] = val
    return result
