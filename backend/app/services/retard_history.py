"""Historique du retard du foyer (v0.22).

Reconstruit le retard quotidien d'un foyer a partir des recurrences et de
l'historique des completions, puis stocke un snapshot par jour. Le backfill
parcourt depuis la 1ere creation de tache du foyer ; le cron daily ajoute
le snapshot du jour a 03h UTC (juste apres `daily_archive_job`).

Filtre "taches en commun au foyer" : on exclut les taches assignees a un
seul user (perso). Une tache sans assignation = visible par tous = comptee.
Une tache assignee a >=2 users = commune = comptee.

Calcul de l'echeance "as of" une date donnee : pure fonction de
`last_completed` via `interval_hours(rule)`. Approximation : on traite
chaque regle comme un cycle de duree moyenne (ex. weekdays Mon/Wed/Fri ~=
2.33j). Suffisant pour une courbe motivante ; pour une vue precise par
tache, /api/retard reste authoritative.
"""

from __future__ import annotations

import bisect
from datetime import date, datetime, timedelta, timezone
from uuid import UUID

import structlog
from sqlalchemy import delete, func, select
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import async_session
from app.models.completion import TaskCompletion
from app.models.foyer import Foyer
from app.models.foyer_retard_snapshot import FoyerRetardSnapshot
from app.models.task import Task
from app.models.task_assignment import TaskAssignment
from app.services.recurrence import interval_hours, is_uncertain

logger = structlog.get_logger()

MINUTES_PER_POINT = 5  # cf projection.py — 1pt d'effort = 5min


def _task_minutes(task: Task) -> int:
    """Effort d'une tache en minutes (1 pt = 5 min, plancher 1 pt)."""
    return max(1, task.weight or 1) * MINUTES_PER_POINT


def _due_after(rule: dict, last_completed: datetime | None, created_at: datetime) -> datetime | None:
    """Echeance d'une tache deduite uniquement de last_completed.

    Pure fonction (pas de dependance a `now()`) : indispensable pour le
    backfill historique. Utilise `interval_hours(rule)` comme delta moyen,
    ce qui lisse les regles non-additives (weekdays, monthly_variable) sans
    perdre l'esprit "cette tache se refait tous les ~N jours".

    Convention :
    - last_completed None : due = created_at (tache jamais faite => en
      retard des creation).
    - rule None ou intervalle infini : pas d'echeance.
    """
    if last_completed is None:
        return created_at
    if rule is None:
        return None
    h = interval_hours(rule)
    if not (h > 0 and h != float("inf")):
        return None
    return last_completed + timedelta(hours=h)


async def _shared_task_ids_for_foyer(session: AsyncSession, foyer_id: UUID) -> set[UUID]:
    """IDs des taches "en commun" du foyer.

    En commun = pas assignee a UN seul user. Donc :
    - 0 assignation (visible par tous) -> commune
    - >=2 assignations -> commune
    - 1 seule assignation -> perso, exclue
    """
    # Compte d'assignations par task pour les taches du foyer.
    counts_q = (
        select(
            TaskAssignment.task_id,
            func.count(TaskAssignment.user_id).label("n"),
        )
        .join(Task, Task.id == TaskAssignment.task_id)
        .where(Task.foyer_id == foyer_id)
        .group_by(TaskAssignment.task_id)
    )
    counts = {row[0]: int(row[1]) for row in (await session.execute(counts_q)).all()}

    # Toutes les tasks du foyer (actives, non archivees, recurrentes, pas
    # uncertain). On filtre le ponctuel et l'incertain ici une fois pour
    # toutes (pas de retard par definition).
    tasks_q = (
        select(Task.id, Task.recurrence_rule)
        .where(
            Task.foyer_id == foyer_id,
            Task.deleted_at.is_(None),
            Task.is_active.is_(True),
            Task.is_archived.is_(False),
            Task.recurrence_rule.isnot(None),
        )
    )
    rows = (await session.execute(tasks_q)).all()

    shared: set[UUID] = set()
    for tid, rule in rows:
        if is_uncertain(rule):
            continue
        n = counts.get(tid, 0)
        if n == 1:
            continue   # perso, exclue
        shared.add(tid)
    return shared


async def _load_compute_inputs(
    session: AsyncSession, foyer_id: UUID
) -> tuple[list[Task], dict[UUID, list[datetime]], date | None]:
    """Charge tasks "en commun" + completions triees + date de la 1ere creation.

    Retour :
      - tasks : list[Task] des taches eligibles (filtrees in-Python)
      - comps_by_task : task_id -> list[datetime] triee asc (binary-search-ready)
      - earliest : date de la 1ere creation de tache du foyer (ou None)
    """
    shared_ids = await _shared_task_ids_for_foyer(session, foyer_id)
    if not shared_ids:
        return [], {}, None

    tasks_q = select(Task).where(Task.id.in_(shared_ids))
    tasks = list((await session.execute(tasks_q)).scalars().all())

    comps_q = (
        select(TaskCompletion.task_id, TaskCompletion.completed_at)
        .where(
            TaskCompletion.task_id.in_(shared_ids),
            TaskCompletion.deleted_at.is_(None),
        )
        .order_by(TaskCompletion.completed_at.asc())
    )
    comps_by_task: dict[UUID, list[datetime]] = {}
    for tid, ca in (await session.execute(comps_q)).all():
        comps_by_task.setdefault(tid, []).append(ca)

    earliest = min((t.created_at for t in tasks if t.created_at), default=None)
    earliest_date = earliest.date() if earliest else None
    return tasks, comps_by_task, earliest_date


def _retard_at(
    tasks: list[Task],
    comps_by_task: dict[UUID, list[datetime]],
    target_date: date,
) -> tuple[int, int]:
    """Retard du foyer a 00h UTC du jour `target_date`.

    "00h UTC" plutot que "fin de journee" : le snapshot represente l'etat au
    debut de la journee, ce qui est aussi ce que le cron 03h UTC capture en
    pratique (peu d'activite avant). Pour `target_date == today`, equivaut
    a "etat au reveil".

    Retourne (total_minutes, task_count) sur les taches eligibles.
    """
    boundary = datetime.combine(target_date, datetime.min.time(), tzinfo=timezone.utc)

    total_min = 0
    n_overdue = 0
    for task in tasks:
        # Tache pas encore creee a cette date : on l'ignore.
        if task.created_at and task.created_at >= boundary:
            continue
        comps = comps_by_task.get(task.id, [])
        # Derniere completion < boundary (strict). bisect_left sur datetimes.
        idx = bisect.bisect_left(comps, boundary)
        last_completed = comps[idx - 1] if idx > 0 else None

        due = _due_after(task.recurrence_rule, last_completed, task.created_at)
        if due is None:
            continue
        if due <= boundary:
            total_min += _task_minutes(task)
            n_overdue += 1
    return total_min, n_overdue


async def backfill_retard(session: AsyncSession, foyer_id: UUID) -> int:
    """(Re)calcule tous les snapshots de la 1ere tache a aujourd'hui inclus.

    Idempotent : utilise un upsert ON CONFLICT pour overwrite les rows
    existantes. Retourne le nb de snapshots ecrits. Ne commit pas — c'est
    a l'appelant.
    """
    tasks, comps, earliest = await _load_compute_inputs(session, foyer_id)
    today = datetime.now(timezone.utc).date()

    if not tasks or earliest is None:
        return 0

    # Borne inferieure : la 1ere creation. On commence au lendemain pour
    # eviter le 1er jour ou created_at serait egal a boundary (la tache
    # n'existe pas encore juste avant minuit du jour de creation).
    cur = earliest
    written = 0
    rows: list[dict] = []
    while cur <= today:
        minutes, count = _retard_at(tasks, comps, cur)
        rows.append({
            "foyer_id": foyer_id,
            "date": cur,
            "total_minutes": minutes,
            "task_count": count,
        })
        written += 1
        cur += timedelta(days=1)
        # Flush par batch de 500 pour ne pas saturer la connexion.
        if len(rows) >= 500:
            await _upsert(session, rows)
            rows.clear()
    if rows:
        await _upsert(session, rows)
    return written


async def _upsert(session: AsyncSession, rows: list[dict]) -> None:
    """Bulk upsert ON CONFLICT (foyer_id, date) DO UPDATE."""
    stmt = pg_insert(FoyerRetardSnapshot).values(rows)
    stmt = stmt.on_conflict_do_update(
        index_elements=["foyer_id", "date"],
        set_={
            "total_minutes": stmt.excluded.total_minutes,
            "task_count": stmt.excluded.task_count,
            "computed_at": func.now(),
        },
    )
    await session.execute(stmt)


async def snapshot_today(session: AsyncSession, foyer_id: UUID) -> tuple[int, int]:
    """Calcule + upsert le snapshot du jour pour un foyer (cron daily).

    Retourne le tuple (minutes, task_count) ecrit. Ne commit pas.
    """
    tasks, comps, _earliest = await _load_compute_inputs(session, foyer_id)
    today = datetime.now(timezone.utc).date()
    minutes, count = _retard_at(tasks, comps, today)
    await _upsert(session, [{
        "foyer_id": foyer_id,
        "date": today,
        "total_minutes": minutes,
        "task_count": count,
    }])
    return minutes, count


async def daily_retard_job() -> None:
    """Job APScheduler 03:15 UTC : un snapshot par foyer, sequentiellement.

    Sequentiel volontaire : evite de saturer la base si le nb de foyers
    grossit. En cas d'erreur sur un foyer, on log et on continue avec les
    suivants — un foyer KO ne fait pas tomber les autres.
    """
    try:
        async with async_session() as session:
            foyer_ids = [
                row[0]
                for row in (await session.execute(select(Foyer.id))).all()
            ]
        for foyer_id in foyer_ids:
            try:
                async with async_session() as session:
                    minutes, count = await snapshot_today(session, foyer_id)
                    await session.commit()
                logger.info(
                    "retard_snapshot_written",
                    foyer_id=str(foyer_id),
                    minutes=minutes,
                    task_count=count,
                )
            except Exception as exc:
                logger.error(
                    "retard_snapshot_failed",
                    foyer_id=str(foyer_id),
                    error=str(exc),
                )
    except Exception as exc:
        logger.error("daily_retard_job_failed", error=str(exc))


async def has_snapshots(session: AsyncSession, foyer_id: UUID) -> bool:
    """True si le foyer a au moins un snapshot (gate d'auto-backfill)."""
    result = await session.execute(
        select(func.count(FoyerRetardSnapshot.date))
        .where(FoyerRetardSnapshot.foyer_id == foyer_id)
    )
    return (result.scalar() or 0) > 0


async def reset_snapshots(session: AsyncSession, foyer_id: UUID) -> int:
    """Vide les snapshots d'un foyer (helper test/recompute force)."""
    result = await session.execute(
        delete(FoyerRetardSnapshot).where(FoyerRetardSnapshot.foyer_id == foyer_id)
    )
    return result.rowcount or 0
