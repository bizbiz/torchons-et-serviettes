"""Recurrence `calendar` — ancrage au calendrier, sans derive.

Ce que ce type apporte et qu'aucun autre ne sait faire : l'echeance ne depend
PAS de la derniere completion. Faire ses impots en juin ne deplace pas
l'echeance de l'annee suivante. Les trois autres types (`interval`,
`monthly_variable`, `uncertain`) ancrent tous sur la derniere fois, ce qui
fait deriver une tache annuelle d'autant qu'on l'a faite en retard.

Les dates sont figees dans chaque test : un calcul de calendrier teste avec
`now()` passe ou echoue selon le jour ou on le lance.
"""

from __future__ import annotations

from datetime import date, datetime, timedelta, timezone

import pytest

from app.models.enums import UrgencyState
from app.services.recurrence import (
    compute_urgency_state,
    grace_days_of,
    interval_hours,
    next_due,
    normalize_recurrence_rule,
)


def _at(y: int, m: int, d: int) -> datetime:
    return datetime(y, m, d, tzinfo=timezone.utc)


# --- Normalisation ---


def test_calendar_normalises_months_and_defaults():
    out = normalize_recurrence_rule(
        {"type": "calendar", "months": ["5", 5, 13, 0, 10], "day": "15"}
    )
    assert out["type"] == "calendar"
    assert out["months"] == [5, 10], "doublons et mois hors bornes non filtres"
    assert out["day"] == 15
    assert out["grace_days"] == 0


def test_calendar_day_is_capped_at_28():
    """Le 31 fevrier n'existe pas : on borne plutot que de lever a l'execution."""
    out = normalize_recurrence_rule({"type": "calendar", "months": [2], "day": 31})
    assert out["day"] == 28


def test_calendar_without_months_falls_back_rather_than_crashing():
    out = normalize_recurrence_rule({"type": "calendar"})
    assert out["months"] == [1]


# --- Types morts : convertis, plus acceptes tels quels ---


@pytest.mark.parametrize(
    "dead,expected",
    [
        ({"type": "monthly", "day_of_month": 1}, 30),
        ({"type": "custom", "every_n_weeks": 2}, 14),
        ({"type": "weekdays", "days": [1, 3, 5]}, 2),
    ],
)
def test_dead_types_become_interval(dead, expected):
    """Avant : acceptes par la validation, sans calcul d'echeance -> tache
    silencieusement inerte, ni rouge ni orange. Desormais convertis."""
    out = normalize_recurrence_rule(dead)
    assert out["type"] == "interval"
    assert out["every_n_days"] == expected
    assert next_due(out, _at(2026, 1, 1)) is not None, "l'echeance doit exister"


# --- Echeance : c'est le calendrier qui decide ---


def test_the_frozen_clock_actually_applies(monkeypatch):
    """Temoin : sans lui, les tests suivants passeraient pour la mauvaise raison.

    Verifie le 2026-08-18, les deux assertions d'ancrage rendent la meme valeur
    avec et sans horloge figee — elles ne prouvaient donc rien. Ce cas-ci choisit
    une date ou les deux mondes DIVERGENT : au 1er fevrier 2026, la derniere
    echeance passee est celle de mai 2025, jamais celle de mai 2026.
    """
    import app.services.recurrence as rec

    rule = normalize_recurrence_rule({"type": "calendar", "months": [5], "day": 15})
    reel = next_due(rule, None)

    class _Feb2026(datetime):
        @classmethod
        def now(cls, tz=None):
            return _at(2026, 2, 1)

    monkeypatch.setattr(rec, "datetime", _Feb2026)
    fige = next_due(rule, None)

    assert fige == _at(2025, 5, 15)
    assert fige != reel, "l'horloge figee n'a eu aucun effet"


def test_taxes_stay_anchored_to_may_even_when_done_late(monkeypatch):
    """Le coeur du type : faites en juin, elles restent dues en mai suivant.

    Avec `interval` ou `monthly_variable`, l'echeance suivante serait juin+N —
    un mois de derive par an, cumulatif.
    """
    import app.services.recurrence as rec

    rule = normalize_recurrence_rule({"type": "calendar", "months": [5], "day": 15})

    class _FixedNow(datetime):
        @classmethod
        def now(cls, tz=None):
            return _at(2026, 7, 1)

    monkeypatch.setattr(rec, "datetime", _FixedNow)
    # Faites avec un mois et demi de retard.
    due = next_due(rule, _at(2026, 6, 30))
    assert due == _at(2027, 5, 15), "l'echeance a derive avec la completion"


def test_an_unhonoured_deadline_stays_due(monkeypatch):
    """Tant qu'on ne l'a pas faite, l'echeance passee reste l'echeance."""
    import app.services.recurrence as rec

    rule = normalize_recurrence_rule({"type": "calendar", "months": [5], "day": 15})

    class _FixedNow(datetime):
        @classmethod
        def now(cls, tz=None):
            return _at(2026, 7, 1)

    monkeypatch.setattr(rec, "datetime", _FixedNow)
    assert next_due(rule, None) == _at(2026, 5, 15)
    assert next_due(rule, _at(2025, 5, 20)) == _at(2026, 5, 15)


def test_two_months_a_year_alternate(monkeypatch):
    """« avril & octobre » = DEUX echeances, pas une fenetre."""
    import app.services.recurrence as rec

    rule = normalize_recurrence_rule({"type": "calendar", "months": [4, 10], "day": 1})

    class _FixedNow(datetime):
        @classmethod
        def now(cls, tz=None):
            return _at(2026, 6, 15)

    monkeypatch.setattr(rec, "datetime", _FixedNow)
    # Avril honore -> la prochaine est octobre, pas avril prochain.
    assert next_due(rule, _at(2026, 4, 3)) == _at(2026, 10, 1)


# --- Tolerance : ce qui distingue « mai-aout » de « avril & octobre » ---


def test_grace_delays_red_but_not_orange():
    """Chaudiere : due en mai, signalee des mai, reprochee seulement apres aout."""
    rule = normalize_recurrence_rule(
        {"type": "calendar", "months": [5], "day": 1, "grace_days": 122}
    )
    grace = grace_days_of(rule)
    due = date(2026, 5, 1)
    nominal = int(interval_hours(rule) / 24)

    assert compute_urgency_state(due, date(2026, 5, 2), nominal, grace) == UrgencyState.ORANGE
    assert compute_urgency_state(due, date(2026, 8, 1), nominal, grace) == UrgencyState.ORANGE
    assert compute_urgency_state(due, date(2026, 9, 15), nominal, grace) == UrgencyState.RED


def test_without_grace_the_day_after_is_red():
    """Controle negatif : sans tolerance, le comportement historique tient."""
    due = date(2026, 5, 1)
    assert compute_urgency_state(due, date(2026, 5, 2), 30, 0) == UrgencyState.RED


def test_nominal_interval_splits_the_year():
    once = normalize_recurrence_rule({"type": "calendar", "months": [5]})
    twice = normalize_recurrence_rule({"type": "calendar", "months": [4, 10]})
    monthly = normalize_recurrence_rule({"type": "calendar", "months": list(range(1, 13))})
    assert interval_hours(once) / 24 == pytest.approx(365, abs=1)
    assert interval_hours(twice) / 24 == pytest.approx(182.5, abs=1)
    assert interval_hours(monthly) / 24 == pytest.approx(30.4, abs=1)
