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
from sqlalchemy import func, literal, select
from sqlalchemy.dialects.postgresql import aggregate_order_by
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.models.completion import TaskCompletion
from app.models.context import Context, TaskContext
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


def _contexts_label():
    """Sous-requete : les contextes d'une tache, ordonnes, en une chaine.

    Correlee sur `Task.id` plutot que jointe : une jointure multiplierait les
    lignes de completion par le nombre de contextes, et le CSV compterait
    chaque tache faite plusieurs fois.
    """
    return (
        select(func.string_agg(
            Context.name,
            aggregate_order_by(literal("; "), TaskContext.position),
        ))
        .select_from(TaskContext)
        .join(Context, Context.id == TaskContext.context_id)
        .where(TaskContext.task_id == Task.id, Context.deleted_at.is_(None))
        .correlate(Task)
        .scalar_subquery()
    )


@router.get("/completions.csv")
async def export_completions(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Historique complet des completions du foyer en CSV.

    Colonnes : completed_at (UTC ISO), task_title, contexts,
    completed_by_display_name, points_earned, notes.
    """
    foyer_uuid = current_foyer_uuid(current)
    result = await session.execute(
        select(
            TaskCompletion.completed_at,
            Task.title,
            _contexts_label().label("contexts"),
            User.display_name,
            TaskCompletion.points_earned,
            TaskCompletion.notes,
        )
        .join(Task, Task.id == TaskCompletion.task_id)
        .join(User, User.id == TaskCompletion.completed_by)
        .where(
            Task.foyer_id == foyer_uuid,
            TaskCompletion.deleted_at.is_(None),
        )
        .order_by(TaskCompletion.completed_at.desc())
    )
    rows: list[list] = [
        ["completed_at", "task", "contexts", "completed_by", "points", "notes"],
    ]
    for row in result.all():
        rows.append([
            row[0].isoformat() if row[0] else "",
            row[1] or "",
            row[2] or "",
            row[3] or "",
            row[4] if row[4] is not None else 0,
            (row[5] or "").replace("\n", " "),
        ])
    return _csv_response(rows, "torchons-completions.csv")


@router.get("/tasks.csv")
async def export_tasks(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Liste des taches actives du foyer en CSV.

    Colonnes : title, contexts, weight, tolerance, recurrence_kind,
    is_active, created_at.
    """
    foyer_uuid = current_foyer_uuid(current)
    result = await session.execute(
        select(
            Task.title,
            _contexts_label().label("contexts"),
            Task.weight,
            Task.tolerance,
            Task.recurrence_rule,
            Task.is_active,
            Task.created_at,
        )
        .where(
            Task.foyer_id == foyer_uuid,
            Task.deleted_at.is_(None),
        )
        .order_by(Task.title)
    )
    rows: list[list] = [
        [
            "title", "contexts", "weight", "tolerance",
            "recurrence_kind", "is_active", "created_at",
        ],
    ]
    for row in result.all():
        recurrence = row[4] or {}
        recurrence_kind = recurrence.get("type") if isinstance(recurrence, dict) else ""
        rows.append([
            row[0] or "",
            row[1] or "",
            row[2] or 0,
            row[3].value if row[3] else "",
            recurrence_kind or "",
            "1" if row[5] else "0",
            row[6].isoformat() if row[6] else "",
        ])
    return _csv_response(rows, "torchons-tasks.csv")
