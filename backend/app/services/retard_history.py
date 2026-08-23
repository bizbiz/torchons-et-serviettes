"""Historique du retard du foyer (v0.37 — aligne sur GET /api/retard).

Reconstruit le retard quotidien d'un foyer et stocke un snapshot par jour.
Depuis v0.37 le modele est STRICTEMENT celui de GET /api/retard :

- Perimetre : toutes les taches actives recurrentes non-uncertain du foyer.
  Plus de filtre "taches en commun" — les taches perso (1 seul assigne) sont
  incluses, comme dans le compteur.
- "En retard" = etat RED du modele jour-granulaire (compute_urgency_state,
  `today > due_date`), et non plus l'ancienne approximation par intervalle
  moyen avec frontiere datetime.
- Home freeze v2 applique historiquement : reconstruit depuis presence_days
  (les jours confirmes absents ne consomment pas l'intervalle des taches
  requires_home).

Le point du JOUR est calcule en live par l'endpoint (retard_now, cutoff =
now) pour coller au compteur meme en cours de journee. Les jours passes
utilisent la frontiere 00h UTC ("etat au reveil"), remplie par le cron
03h15 (snapshot_today) ou le backfill.

Toutes les fonctions de reconstruction restent des fonctions PURES de
l'historique (completions + presence_days) : indispensable pour rejouer le
passe sans dependre de now().
"""

from __future__ import annotations

import bisect
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta, timezone
from uuid import UUID

import structlog
from sqlalchemy import delete, func, select
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import async_session
from app.models.completion import TaskCompletion
from app.models.enums import UrgencyState
from app.models.foyer import Foyer
from app.models.foyer_retard_snapshot import FoyerRetardSnapshot
from app.models.presence_day import PresenceDay
from app.models.task import Task
from app.services.task_flags import task_ids_freezing_when_away
from app.services.recurrence import (
    compute_due_date,
    compute_effective_today,
    compute_nominal_interval_days,
    compute_urgency_state,
    is_uncertain,
    grace_days_of,
)

logger = structlog.get_logger()

MINUTES_PER_POINT = 5  # cf projection.py — 1pt d'effort = 5min


def _task_minutes(task: Task) -> int:
    """Effort d'une tache en minutes (1 pt = 5 min, plancher 1 pt)."""
    return max(1, task.weight or 1) * MINUTES_PER_POINT


def _midnight(d: date) -> datetime:
    """00h UTC du jour `d` (frontiere "etat au reveil")."""
    return datetime.combine(d, time.min, tzinfo=timezone.utc)


@dataclass
class _ComputeInputs:
    """Donnees chargees une fois pour rejouer le retard sur toutes les dates."""

    tasks: list[Task]
    comps_by_task: dict[UUID, list[datetime]]
    earliest_date: date | None
    freeze_enabled: bool
    #: Par TACHE : gele-t-elle pendant une absence ? Resolu depuis ses
    #: contextes (presence exigee ET besoin qui ne monte pas tout seul).
    freezes_by_task: dict[UUID, bool]
    present_dates: list[date]  # dates confirmed_present, triees asc


async def _load_compute_inputs(session: AsyncSession, foyer_id: UUID) -> _ComputeInputs:
    """Charge le perimetre + completions + contexte home freeze du foyer.

    Perimetre identique a /api/retard : toutes les taches actives, non
    archivees, recurrentes (rule non nulle) et non-uncertain. Aucun filtre
    d'assignation.
    """
    tasks_q = select(Task).where(
        Task.foyer_id == foyer_id,
        Task.deleted_at.is_(None),
        Task.is_active.is_(True),
        Task.is_archived.is_(False),
        Task.recurrence_rule.isnot(None),
    )
    tasks = [
        t
        for t in (await session.execute(tasks_q)).scalars().all()
        if not is_uncertain(t.recurrence_rule)
    ]
    if not tasks:
        return _ComputeInputs([], {}, None, False, {}, [])

    task_ids = [t.id for t in tasks]
    comps_q = (
        select(TaskCompletion.task_id, TaskCompletion.completed_at)
        .where(
            TaskCompletion.task_id.in_(task_ids),
            TaskCompletion.deleted_at.is_(None),
        )
        .order_by(TaskCompletion.completed_at.asc())
    )
    comps_by_task: dict[UUID, list[datetime]] = {}
    for tid, ca in (await session.execute(comps_q)).all():
        comps_by_task.setdefault(tid, []).append(ca)

    earliest = min((t.created_at for t in tasks if t.created_at), default=None)
    earliest_date = earliest.date() if earliest else None

    # Home freeze v2 : foyer + contextes gelants + presence_days.
    foyer = (
        await session.execute(select(Foyer).where(Foyer.id == foyer_id))
    ).scalar_one_or_none()
    freeze_enabled = foyer.home_freeze_enabled if foyer else False
    freezes_by_task: dict[UUID, bool] = {}
    present_dates: list[date] = []
    if freeze_enabled:
        gelantes = await task_ids_freezing_when_away(session, [t.id for t in tasks])
        freezes_by_task = {t.id: t.id in gelantes for t in tasks}
        pres_rows = (
            await session.execute(
                select(PresenceDay.date).where(
                    PresenceDay.foyer_id == foyer_id,
                    PresenceDay.status == "confirmed_present",
                )
            )
        ).all()
        present_dates = sorted(row[0] for row in pres_rows)

    return _ComputeInputs(
        tasks, comps_by_task, earliest_date, freeze_enabled, freezes_by_task, present_dates
    )


def _absent_days(present_dates: list[date], since_date: date, until_date: date) -> int:
    """Jours absents dans (since_date, until_date), bornes exclues.

    Meme convention que presence.get_absent_days_map : les jours sans ligne
    confirmed_present comptent comme absents. absent = total - presents.
    """
    start = since_date + timedelta(days=1)
    if start >= until_date:
        return 0
    total = (until_date - start).days
    lo = bisect.bisect_left(present_dates, start)
    hi = bisect.bisect_left(present_dates, until_date)
    return total - (hi - lo)


def _retard_at(inp: _ComputeInputs, target_date: date, boundary: datetime) -> tuple[int, int]:
    """Retard du foyer a `target_date`, completions coupees a `boundary`.

    - target_date : date de comparaison du modele jour (RED = target > due).
    - boundary : cutoff datetime des completions prises en compte. Jour passe
      = 00h UTC de target_date ; point live du jour = now().

    Retourne (total_minutes, task_count) — memes conventions que /api/retard.
    """
    total_min = 0
    n_overdue = 0
    for task in inp.tasks:
        # Tache pas encore creee a cette frontiere : ignoree.
        if task.created_at and task.created_at >= boundary:
            continue
        comps = inp.comps_by_task.get(task.id, [])
        idx = bisect.bisect_left(comps, boundary)
        last_completed = comps[idx - 1] if idx > 0 else None

        due_date = compute_due_date(task.recurrence_rule, last_completed, task.created_at)
        if due_date is None:
            continue
        nominal = compute_nominal_interval_days(task.recurrence_rule)

        requires_home = inp.freezes_by_task.get(task.id, False)
        absent = 0
        if inp.freeze_enabled and requires_home:
            since = (
                last_completed.date()
                if last_completed
                else (task.created_at.date() if task.created_at else target_date)
            )
            absent = _absent_days(inp.present_dates, since, target_date)
        eff_today = compute_effective_today(
            target_date,
            task_requires_home=requires_home,
            home_freeze_enabled=inp.freeze_enabled,
            absent_days=absent,
        )

        if compute_urgency_state(due_date, eff_today, nominal, grace_days_of(task.recurrence_rule)) == UrgencyState.RED:
            total_min += _task_minutes(task)
            n_overdue += 1
    return total_min, n_overdue


async def backfill_retard(session: AsyncSession, foyer_id: UUID) -> int:
    """(Re)calcule tous les snapshots de la 1ere tache a aujourd'hui inclus.

    Idempotent (upsert ON CONFLICT). Retourne le nb de snapshots ecrits. Ne
    commit pas — c'est a l'appelant. Chaque jour passe utilise la frontiere
    00h UTC ("etat au reveil").
    """
    inp = await _load_compute_inputs(session, foyer_id)
    today = datetime.now(timezone.utc).date()

    if not inp.tasks or inp.earliest_date is None:
        return 0

    cur = inp.earliest_date
    written = 0
    rows: list[dict] = []
    while cur <= today:
        minutes, count = _retard_at(inp, cur, _midnight(cur))
        rows.append(
            {
                "foyer_id": foyer_id,
                "date": cur,
                "total_minutes": minutes,
                "task_count": count,
            }
        )
        written += 1
        cur += timedelta(days=1)
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
    """Calcule + upsert le snapshot du jour (cron daily), frontiere 00h UTC.

    Retourne le tuple (minutes, task_count) ecrit. Ne commit pas.
    """
    inp = await _load_compute_inputs(session, foyer_id)
    today = datetime.now(timezone.utc).date()
    minutes, count = _retard_at(inp, today, _midnight(today))
    await _upsert(
        session,
        [
            {
                "foyer_id": foyer_id,
                "date": today,
                "total_minutes": minutes,
                "task_count": count,
            }
        ],
    )
    return minutes, count


async def retard_now(session: AsyncSession, foyer_id: UUID) -> tuple[int, int]:
    """Retard LIVE du foyer (cutoff = now) — point du jour de la timeline.

    Strictement identique a GET /api/retard : meme perimetre, meme modele
    jour-granulaire, meme home freeze. Sert a afficher un dernier point de
    courbe coherent avec le gros chiffre, meme en cours de journee.
    """
    inp = await _load_compute_inputs(session, foyer_id)
    today = datetime.now(timezone.utc).date()
    return _retard_at(inp, today, datetime.now(timezone.utc))


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
        select(func.count(FoyerRetardSnapshot.date)).where(
            FoyerRetardSnapshot.foyer_id == foyer_id
        )
    )
    return (result.scalar() or 0) > 0


async def reset_snapshots(session: AsyncSession, foyer_id: UUID) -> int:
    """Vide les snapshots d'un foyer (helper test/recompute force)."""
    result = await session.execute(
        delete(FoyerRetardSnapshot).where(FoyerRetardSnapshot.foyer_id == foyer_id)
    )
    return result.rowcount or 0
