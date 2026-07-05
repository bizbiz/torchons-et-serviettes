"""Unit tests for the /api/retard overdue classification.

Guards the v0.36 fix that migrated GET /api/retard from the legacy
`is_overdue` (datetime granularity, ignores home freeze) to the canonical
day-granular urgency model (`compute_urgency_state == RED` + home freeze),
so the Home "Foyer" card matches the Tasks tab red cards.

These are DB-free: they exercise the exact pure functions the endpoint now
composes (compute_due_date / compute_effective_today / compute_urgency_state),
and contrast them with the old is_overdue to make the behavioral change
explicit and regression-proof.
"""

from __future__ import annotations

from datetime import date, datetime, timedelta, timezone

from app.models.enums import UrgencyState
from app.services.recurrence import (
    compute_due_date,
    compute_effective_today,
    compute_nominal_interval_days,
    compute_urgency_state,
    is_overdue,
)

TODAY = date(2026, 7, 5)
INTERVAL_7 = {"type": "interval", "every_n_days": 7}
INTERVAL_30 = {"type": "interval", "every_n_days": 30}


def _dt(d: date) -> datetime:
    return datetime(d.year, d.month, d.day, 12, 0, tzinfo=timezone.utc)


def _is_retard(rule, last_completed, created_at, *, absent_days=0, requires_home=False, freeze=False) -> bool:
    """Reproduit la decision de get_retard (etat RED) pour une tache."""
    due_date = compute_due_date(rule, last_completed, created_at)
    nominal = compute_nominal_interval_days(rule)
    eff_today = compute_effective_today(
        TODAY,
        task_requires_home=requires_home,
        home_freeze_enabled=freeze,
        absent_days=absent_days,
    )
    return compute_urgency_state(due_date, eff_today, nominal) == UrgencyState.RED


def test_never_completed_created_today_is_not_retard():
    """Cas 1 : tache jamais faite creee aujourd'hui -> plus comptee en retard.

    Ancien comportement (is_overdue) : en retard immediatement. Nouveau : non.
    """
    created_today = _dt(TODAY)
    assert _is_retard(INTERVAL_7, None, created_today) is False
    # Contraste avec l'ancien modele qui la marquait en retard.
    assert is_overdue(INTERVAL_7, None) is True


def test_never_completed_created_yesterday_is_retard():
    """Une tache jamais faite devient rouge des le lendemain de sa creation."""
    created_yesterday = _dt(TODAY - timedelta(days=1))
    assert _is_retard(INTERVAL_7, None, created_yesterday) is True


def test_due_today_is_not_retard():
    """Cas 2 : echeance == aujourd'hui -> ORANGE, pas en retard (RED strict).

    Ancien is_overdue (datetime, due <= now) la comptait des l'heure passee.
    """
    last = _dt(TODAY - timedelta(days=30))  # due = today
    assert compute_due_date(INTERVAL_30, last, None) == TODAY
    assert _is_retard(INTERVAL_30, last, None) is False


def test_due_yesterday_is_retard():
    """Echeance depassee d'un jour -> en retard."""
    last = _dt(TODAY - timedelta(days=31))  # due = today - 1
    assert _is_retard(INTERVAL_30, last, None) is True


def test_home_freeze_suppresses_retard():
    """Cas 3 : home freeze recule effective_today -> plus en retard.

    Tache requires_home due hier, mais 2 jours d'absence confirmes :
    effective_today = today - 2 <= due_date, donc pas RED.
    """
    last = _dt(TODAY - timedelta(days=8))  # interval 7 -> due = today - 1
    # Sans freeze : en retard.
    assert _is_retard(INTERVAL_7, last, None) is True
    # Avec freeze + 2 jours absents sur une tache requires_home : gelee.
    assert _is_retard(
        INTERVAL_7, last, None, absent_days=2, requires_home=True, freeze=True
    ) is False


def test_home_freeze_ignored_when_not_requires_home():
    """Le freeze ne s'applique qu'aux taches requires_home."""
    last = _dt(TODAY - timedelta(days=8))  # due = today - 1
    assert _is_retard(
        INTERVAL_7, last, None, absent_days=2, requires_home=False, freeze=True
    ) is True
