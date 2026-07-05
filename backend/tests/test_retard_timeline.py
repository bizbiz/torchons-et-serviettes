"""Unit tests for the retard history/timeline model (v0.37 alignment).

DB-free tests on the pure reconstruction functions of retard_history, which
now mirror GET /api/retard exactly (day-granular RED + home freeze). Locks
the historical replay: due-today not counted, home freeze suppresses retard,
absent-day counting matches presence.get_absent_days_map conventions.
"""

from __future__ import annotations

import uuid
from datetime import date, datetime, timedelta, timezone

from app.models.task import Task
from app.services.retard_history import (
    _ComputeInputs,
    _absent_days,
    _midnight,
    _retard_at,
)

TD = date(2026, 7, 5)
INTERVAL_7 = {"type": "interval", "every_n_days": 7}
INTERVAL_30 = {"type": "interval", "every_n_days": 30}
CAT_HOME = uuid.uuid4()


def _dt(d: date) -> datetime:
    return datetime(d.year, d.month, d.day, 12, 0, tzinfo=timezone.utc)


def _mk_task(rule, created: date, category_id=None, weight=1) -> Task:
    t = Task()
    t.id = uuid.uuid4()
    t.title = "x"
    t.recurrence_rule = rule
    t.created_at = _dt(created)
    t.category_id = category_id
    t.weight = weight
    return t


def _inputs(task, comps, *, freeze=False, requires_home=False, present=None):
    return _ComputeInputs(
        tasks=[task],
        comps_by_task={task.id: comps},
        earliest_date=task.created_at.date(),
        freeze_enabled=freeze,
        requires_home_map={CAT_HOME: requires_home},
        present_dates=sorted(present or []),
    )


def test_absent_days_counts_days_without_present_row():
    # Fenetre (TD-8, TD) exclus -> jours TD-7..TD-1 = 7 jours. Aucun present.
    assert _absent_days([], TD - timedelta(days=8), TD) == 7
    # Deux jours confirmes presents dans la fenetre -> 5 absents.
    present = [TD - timedelta(days=3), TD - timedelta(days=2)]
    assert _absent_days(present, TD - timedelta(days=8), TD) == 5
    # since >= until -> 0.
    assert _absent_days([], TD, TD) == 0


def test_due_today_not_counted():
    task = _mk_task(INTERVAL_30, TD - timedelta(days=40))
    comps = [_dt(TD - timedelta(days=30))]  # due = today
    minutes, count = _retard_at(_inputs(task, comps), TD, _midnight(TD))
    assert count == 0


def test_overdue_counted():
    task = _mk_task(INTERVAL_30, TD - timedelta(days=40))
    comps = [_dt(TD - timedelta(days=31))]  # due = today - 1
    minutes, count = _retard_at(_inputs(task, comps), TD, _midnight(TD))
    assert count == 1


def test_home_freeze_suppresses_historical_retard():
    task = _mk_task(INTERVAL_7, TD - timedelta(days=40), category_id=CAT_HOME)
    comps = [_dt(TD - timedelta(days=8))]  # due = today - 1 -> rouge sans freeze

    # Sans freeze : en retard.
    _, count_off = _retard_at(_inputs(task, comps), TD, _midnight(TD))
    assert count_off == 1

    # Avec freeze + tous les jours absents (present_dates vide) : gelee.
    inp = _inputs(task, comps, freeze=True, requires_home=True, present=[])
    _, count_on = _retard_at(inp, TD, _midnight(TD))
    assert count_on == 0


def test_task_created_after_boundary_ignored():
    # Tache creee demain : n'existe pas encore a TD -> ignoree.
    task = _mk_task(INTERVAL_7, TD + timedelta(days=1))
    minutes, count = _retard_at(_inputs(task, []), TD, _midnight(TD))
    assert count == 0
