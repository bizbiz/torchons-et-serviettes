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
    """Synchronise les operations offline du client et retourne les changements serveur."""
    user_id = current["user_id"]
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
            result = await _apply_op(session, op, user_id, body.device_id)
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
    # en excluant les ops originant de ce device
    own_op_ids = select(ProcessedOp.client_op_id).where(
        ProcessedOp.device_id == body.device_id
    ).scalar_subquery()

    server_rows = (
        await session.execute(
            select(ChangeLog)
            .where(ChangeLog.server_version > body.last_server_version)
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
    device_id: uuid.UUID,
) -> SyncAppliedOp:
    """Dispatch et execute une operation sync."""
    handlers = {
        "tasks": _handle_task,
        "categories": _handle_category,
        "task_completions": _handle_completion,
        "user_settings": _handle_settings,
    }
    handler = handlers.get(op.table)
    if handler is None:
        raise HTTPException(status_code=400, detail=f"Table inconnue: {op.table}")

    server_row_id = await handler(session, op, user_id)

    # Enregistrer dans processed_ops
    session.add(ProcessedOp(
        client_op_id=op.client_op_id,
        table_name=op.table,
        server_row_id=server_row_id,
        device_id=device_id,
    ))

    # Enregistrer dans change_log
    await record_change(
        session, op.table, server_row_id, op.operation, user_id,
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
) -> uuid.UUID:
    """Traite INSERT / UPDATE / DELETE sur tasks."""
    data = op.data or {}

    if op.operation == "INSERT":
        task = Task(
            id=op.row_id,
            title=data.get("title", "Sans titre"),
            description=data.get("description"),
            category_id=uuid.UUID(data["category_id"]) if data.get("category_id") else None,
            recurrence_rule=data.get("recurrence_rule"),
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
                select(Task).where(Task.id == op.row_id, Task.deleted_at.is_(None))
            )
        ).scalar_one_or_none()
        if not row:
            raise StaleUpdateError()
        if _truncate_to_ms(row.updated_at) > _truncate_to_ms(op.client_timestamp):
            raise StaleUpdateError(_row_to_dict(row))
        for key, val in data.items():
            if key == "assigned_to_ids":
                await session.execute(
                    delete(TaskAssignment).where(TaskAssignment.task_id == op.row_id)
                )
                for uid in val:
                    session.add(TaskAssignment(task_id=op.row_id, user_id=uuid.UUID(uid)))
            elif hasattr(row, key):
                setattr(row, key, val)
        row.version += 1
        return row.id

    if op.operation == "DELETE":
        row = (
            await session.execute(
                select(Task).where(Task.id == op.row_id, Task.deleted_at.is_(None))
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
) -> uuid.UUID:
    """Traite INSERT / UPDATE / DELETE sur categories."""
    data = op.data or {}

    if op.operation == "INSERT":
        cat = Category(
            id=op.row_id,
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
                select(Category).where(Category.id == op.row_id, Category.deleted_at.is_(None))
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
                select(Category).where(Category.id == op.row_id, Category.deleted_at.is_(None))
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
) -> uuid.UUID:
    """Traite INSERT / DELETE sur task_completions."""
    data = op.data or {}

    if op.operation == "INSERT":
        comp = TaskCompletion(
            id=op.row_id,
            task_id=uuid.UUID(data["task_id"]) if data.get("task_id") else op.row_id,
            completed_by=uuid.UUID(data.get("completed_by", user_id)),
            points_earned=data.get("points_earned", 1),
            notes=data.get("notes"),
        )
        if data.get("completed_at"):
            comp.completed_at = datetime.fromisoformat(data["completed_at"])
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

    raise HTTPException(status_code=400, detail=f"Operation non supportee pour completions: {op.operation}")


async def _handle_settings(
    session: AsyncSession,
    op: SyncOperation,
    user_id: str,
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
