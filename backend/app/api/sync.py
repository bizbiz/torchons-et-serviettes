"""Endpoint POST /api/sync — synchronisation offline bidirectionnelle."""

from __future__ import annotations

import json
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import delete, func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.models.category import Category
from app.models.completion import TaskCompletion
from app.models.enums import CompletionReason
from app.models.sync import ChangeLog, ProcessedOp
from app.models.task import Task
from app.models.task_assignment import TaskAssignment
from app.models.user_settings import UserSettings
from app.schemas.sync import (
    ChangeLogEntry,
    SyncAppliedOp,
    SyncConflict,
    SyncOperation,
    SyncRequest,
    SyncResponse,
)
from app.services.bonus_malus import (
    apply_multiplier_to_points,
    compute_base_points_for_task,
    compute_bonus_malus_for_completion,
    resolve_stored_reason_and_mult,
)
from app.services.recurrence import normalize_recurrence_rule
from app.utils.changelog import record_change

router = APIRouter(prefix="/api", tags=["sync"])


class StaleUpdateError(Exception):
    """Le client tente de modifier une row plus recente cote serveur."""
    def __init__(self, server_data: dict | None = None):
        self.server_data = server_data


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
            result = await _apply_op(session, op, user_id, foyer_id, body.device_id)
            applied.append(result)
        except StaleUpdateError as e:
            conflicts.append(SyncConflict(
                client_op_id=op.client_op_id,
                table=op.table,
                row_id=op.row_id,
                server_data=e.server_data,
                reason="stale_update",
            ))
        except IntegrityError:
            await session.rollback()
            conflicts.append(SyncConflict(
                client_op_id=op.client_op_id,
                table=op.table,
                row_id=op.row_id,
                reason="unique_violation",
            ))

    await session.commit()

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
        "categories": _handle_category,
        "task_completions": _handle_completion,
        "user_settings": _handle_settings,
    }
    handler = handlers.get(op.table)
    if handler is None:
        raise HTTPException(status_code=400, detail=f"Table inconnue: {op.table}")

    server_row_id = await handler(session, op, user_id, foyer_id)

    # Enregistrer dans processed_ops
    session.add(ProcessedOp(
        client_op_id=op.client_op_id,
        table_name=op.table,
        server_row_id=server_row_id,
        device_id=device_id,
    ))

    # Enregistrer dans change_log (avec foyer_id)
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


def _truncate_to_ms(dt: datetime) -> datetime:
    """Tronque un datetime a la milliseconde (resolution Android)."""
    return dt.replace(microsecond=(dt.microsecond // 1000) * 1000)


async def _handle_task(
    session: AsyncSession,
    op: SyncOperation,
    user_id: str,
    foyer_id: str,
) -> uuid.UUID:
    """Traite INSERT / UPDATE / DELETE sur tasks."""
    data = op.data or {}
    foyer_uuid = uuid.UUID(foyer_id) if isinstance(foyer_id, str) else foyer_id

    if op.operation == "INSERT":
        task = Task(
            id=op.row_id,
            foyer_id=foyer_uuid,
            title=data.get("title", "Sans titre"),
            description=data.get("description"),
            category_id=uuid.UUID(data["category_id"]) if data.get("category_id") else None,
            room_id=uuid.UUID(data["room_id"]) if data.get("room_id") else None,
            recurrence_rule=normalize_recurrence_rule(data.get("recurrence_rule")),
            weight=data.get("weight", 1),
            tolerance=data.get("tolerance", "NORMAL"),
        )
        session.add(task)
        await session.flush()
        for uid in data.get("assigned_to_ids", []):
            session.add(TaskAssignment(task_id=task.id, user_id=uuid.UUID(uid)))
        return task.id

    if op.operation == "UPDATE":
        row = (
            await session.execute(
                select(Task).where(
                    Task.id == op.row_id,
                    Task.foyer_id == foyer_uuid,
                    Task.deleted_at.is_(None),
                )
            )
        ).scalar_one_or_none()
        if not row:
            raise StaleUpdateError()
        if _truncate_to_ms(row.updated_at) > _truncate_to_ms(op.client_timestamp):
            raise StaleUpdateError(_row_to_dict(row))
        # is_archived n'est jamais ecrit par le client : seul le job backend
        # d'auto-archivage le toggle. Defense en profondeur si un vieux client
        # renvoie le champ par inadvertance.
        data.pop("is_archived", None)
        for key, val in data.items():
            if key == "assigned_to_ids":
                await session.execute(
                    delete(TaskAssignment).where(TaskAssignment.task_id == op.row_id)
                )
                for uid in val:
                    session.add(TaskAssignment(task_id=op.row_id, user_id=uuid.UUID(uid)))
            elif key == "recurrence_rule":
                setattr(row, key, normalize_recurrence_rule(val))
            elif hasattr(row, key):
                setattr(row, key, val)
        row.version += 1
        return row.id

    if op.operation == "DELETE":
        row = (
            await session.execute(
                select(Task).where(
                    Task.id == op.row_id,
                    Task.foyer_id == foyer_uuid,
                    Task.deleted_at.is_(None),
                )
            )
        ).scalar_one_or_none()
        if row:
            row.deleted_at = datetime.now(timezone.utc)
            row.version += 1
        return op.row_id

    raise HTTPException(status_code=400, detail=f"Operation inconnue: {op.operation}")


async def _handle_category(
    session: AsyncSession,
    op: SyncOperation,
    user_id: str,
    foyer_id: str,
) -> uuid.UUID:
    """Traite INSERT / UPDATE / DELETE sur categories."""
    data = op.data or {}
    foyer_uuid = uuid.UUID(foyer_id) if isinstance(foyer_id, str) else foyer_id

    if op.operation == "INSERT":
        cat = Category(
            id=op.row_id,
            foyer_id=foyer_uuid,
            name=data.get("name", "Sans nom"),
            color=data.get("color", "#6B7280"),
            icon=data.get("icon"),
            requires_home=data.get("requires_home", False),
        )
        session.add(cat)
        await session.flush()
        return cat.id

    if op.operation == "UPDATE":
        row = (
            await session.execute(
                select(Category).where(
                    Category.id == op.row_id,
                    Category.foyer_id == foyer_uuid,
                    Category.deleted_at.is_(None),
                )
            )
        ).scalar_one_or_none()
        if not row:
            raise StaleUpdateError()
        if _truncate_to_ms(row.updated_at) > _truncate_to_ms(op.client_timestamp):
            raise StaleUpdateError(_row_to_dict(row))
        for key, val in data.items():
            if hasattr(row, key):
                setattr(row, key, val)
        row.version += 1
        return row.id

    if op.operation == "DELETE":
        row = (
            await session.execute(
                select(Category).where(
                    Category.id == op.row_id,
                    Category.foyer_id == foyer_uuid,
                    Category.deleted_at.is_(None),
                )
            )
        ).scalar_one_or_none()
        if row:
            row.deleted_at = datetime.now(timezone.utc)
            row.version += 1
        return op.row_id

    raise HTTPException(status_code=400, detail=f"Operation inconnue: {op.operation}")


async def _handle_completion(
    session: AsyncSession,
    op: SyncOperation,
    user_id: str,
    foyer_id: str,
) -> uuid.UUID:
    """Traite INSERT / UPDATE / DELETE sur task_completions.

    Sur INSERT : applique le systeme bonus/malus (points_earned + multiplier
    + reason) pour rester coherent avec le endpoint REST POST /complete. Le
    payload client n'a que {task_id, completed_by, completed_at, notes} —
    le back recalcule autoritairement.

    Sur UPDATE : applique completed_by / completed_at / notes (edition d'une
    completion offline-first). Memes effets que le REST PUT /completions/{id}
    (stats.py) — pas de recalcul des points, coherent avec ce endpoint.
    """
    data = op.data or {}

    if op.operation == "INSERT":
        # Charger la tache pour calculer le multiplicateur.
        task_id = uuid.UUID(data["task_id"]) if data.get("task_id") else op.row_id
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

        if task is not None:
            base_points = await compute_base_points_for_task(session, task)
            mult_result = await compute_bonus_malus_for_completion(
                session, task, uuid.UUID(foyer_id), completed_at_eff,
            )
            reason, multiplier = resolve_stored_reason_and_mult(base_points, mult_result)
            points = apply_multiplier_to_points(base_points, mult_result)

        comp = TaskCompletion(
            id=op.row_id,
            task_id=task_id,
            completed_by=uuid.UUID(data.get("completed_by", user_id)),
            points_earned=points,
            multiplier=multiplier,
            reason=reason,
            notes=data.get("notes"),
        )
        comp.completed_at = completed_at_eff
        session.add(comp)
        await session.flush()
        return comp.id

    if op.operation == "DELETE":
        row = (
            await session.execute(
                select(TaskCompletion).where(
                    TaskCompletion.id == op.row_id,
                    TaskCompletion.deleted_at.is_(None),
                )
            )
        ).scalar_one_or_none()
        if row:
            row.deleted_at = datetime.now(timezone.utc)
        return op.row_id

    if op.operation == "UPDATE":
        foyer_uuid = uuid.UUID(foyer_id) if isinstance(foyer_id, str) else foyer_id
        row = (
            await session.execute(
                select(TaskCompletion)
                .join(Task, Task.id == TaskCompletion.task_id)
                .where(
                    TaskCompletion.id == op.row_id,
                    TaskCompletion.deleted_at.is_(None),
                    Task.foyer_id == foyer_uuid,
                )
            )
        ).scalar_one_or_none()
        if row:
            if data.get("completed_by"):
                row.completed_by = uuid.UUID(data["completed_by"])
            if data.get("completed_at"):
                ca = datetime.fromisoformat(data["completed_at"])
                if ca.tzinfo is None:
                    ca = ca.replace(tzinfo=timezone.utc)
                row.completed_at = ca
            if "notes" in data:
                row.notes = data.get("notes")
            row.version += 1
        return op.row_id

    raise HTTPException(status_code=400, detail=f"Operation non supportee pour completions: {op.operation}")


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
        row = (
            await session.execute(
                select(UserSettings).where(UserSettings.user_id == uid)
            )
        ).scalar_one_or_none()
        if not row:
            row = UserSettings(user_id=uid)
            session.add(row)
            await session.flush()
        for key, val in data.items():
            if hasattr(row, key) and key != "user_id":
                setattr(row, key, val)
        return uid

    raise HTTPException(status_code=400, detail=f"Operation non supportee pour settings: {op.operation}")


def _row_to_dict(row) -> dict:
    """Serialise une row SQLAlchemy en dict pour les conflits."""
    result = {}
    for c in row.__table__.columns:
        val = getattr(row, c.key, None)
        if isinstance(val, (uuid.UUID, datetime)):
            val = str(val)
        result[c.key] = val
    return result
