"""Endpoints d'export CSV (v0.31.x).

Exports utiles pour analyse externe (Excel, Numbers, scripts perso) :
- GET /api/export/completions.csv : historique de completions du foyer.
- GET /api/export/tasks.csv      : taches actives du foyer avec leurs metas.

Pas d'authentification anonyme — JWT requis. Scope foyer via foyer_id du
JWT.

Pas d'export ICS pour l'instant — la generation iCalendar demande de
materialiser les occurrences futures recurrence par recurrence, ce qui
sera fait quand on attaquera le planning partage (cf. roadmap).
"""

from __future__ import annotations

import csv
import io
from uuid import UUID

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.models.category import Category
from app.models.completion import TaskCompletion
from app.models.room import Room
from app.models.task import Task
from app.models.user import User
from app.utils.scoping import current_foyer_uuid

router = APIRouter(prefix="/api/export", tags=["export"])


def _csv_response(rows: list[list], filename: str) -> StreamingResponse:
    """Helper : streame `rows` (header en row[0]) en text/csv."""
    buf = io.StringIO()
    writer = csv.writer(buf, dialect="excel", quoting=csv.QUOTE_MINIMAL)
    for row in rows:
        writer.writerow(row)
    buf.seek(0)
    return StreamingResponse(
        iter([buf.getvalue()]),
        media_type="text/csv; charset=utf-8",
        headers={
            "Content-Disposition": f'attachment; filename="{filename}"',
        },
    )


@router.get("/completions.csv")
async def export_completions(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Historique complet des completions du foyer en CSV.

    Colonnes : completed_at (UTC ISO), task_title, room, category,
    completed_by_display_name, points_earned, notes.
    """
    foyer_uuid = current_foyer_uuid(current)
    result = await session.execute(
        select(
            TaskCompletion.completed_at,
            Task.title,
            Room.name,
            Category.name,
            User.display_name,
            TaskCompletion.points_earned,
            TaskCompletion.notes,
        )
        .join(Task, Task.id == TaskCompletion.task_id)
        .outerjoin(Room, Room.id == Task.room_id)
        .outerjoin(Category, Category.id == Task.category_id)
        .join(User, User.id == TaskCompletion.completed_by)
        .where(
            Task.foyer_id == foyer_uuid,
            TaskCompletion.deleted_at.is_(None),
        )
        .order_by(TaskCompletion.completed_at.desc())
    )
    rows: list[list] = [
        ["completed_at", "task", "room", "category", "completed_by", "points", "notes"],
    ]
    for row in result.all():
        rows.append([
            row[0].isoformat() if row[0] else "",
            row[1] or "",
            row[2] or "",
            row[3] or "",
            row[4] or "",
            row[5] if row[5] is not None else 0,
            (row[6] or "").replace("\n", " "),
        ])
    return _csv_response(rows, "torchons-completions.csv")


@router.get("/tasks.csv")
async def export_tasks(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Liste des taches actives du foyer en CSV.

    Colonnes : title, room, category, weight, tolerance, recurrence_kind,
    is_active, created_at.
    """
    foyer_uuid = current_foyer_uuid(current)
    result = await session.execute(
        select(
            Task.title,
            Room.name,
            Category.name,
            Task.weight,
            Task.tolerance,
            Task.recurrence_rule,
            Task.is_active,
            Task.created_at,
        )
        .outerjoin(Room, Room.id == Task.room_id)
        .outerjoin(Category, Category.id == Task.category_id)
        .where(
            Task.foyer_id == foyer_uuid,
            Task.deleted_at.is_(None),
        )
        .order_by(Task.title)
    )
    rows: list[list] = [
        [
            "title", "room", "category", "weight", "tolerance",
            "recurrence_kind", "is_active", "created_at",
        ],
    ]
    for row in result.all():
        recurrence = row[5] or {}
        recurrence_kind = recurrence.get("type") if isinstance(recurrence, dict) else ""
        rows.append([
            row[0] or "",
            row[1] or "",
            row[2] or "",
            row[3] or 0,
            row[4].value if row[4] else "",
            recurrence_kind or "",
            "1" if row[6] else "0",
            row[7].isoformat() if row[7] else "",
        ])
    return _csv_response(rows, "torchons-tasks.csv")
