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
    compute_nominal_interval_days,
    compute_urgency_state,
    grace_days_of,
    interval_hours,
    next_due,
    normalize_recurrence_rule,
    orange_window_cap_of,
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


def test_dead_monthly_becomes_calendar_every_month():
    """A fixed day every month is a calendar rule, not a drifting interval 30
    (decided 2026-09-30). Same output as migration d4ec241658ca."""
    out = normalize_recurrence_rule({"type": "monthly", "day_of_month": 1})
    assert out == {"type": "calendar", "months": list(range(1, 13)), "day": 1, "grace_days": 0}
    # Done late on the 20th: the next one is still the 1st, no drift.
    assert next_due(out, _at(2026, 7, 20)).date() > date(2026, 7, 20)
    assert next_due(out, _at(2026, 7, 20)).day == 1


def test_dead_monthly_day_is_capped_like_calendar():
    out = normalize_recurrence_rule({"type": "monthly", "day_of_month": 31})
    assert out["day"] == 28


@pytest.mark.parametrize(
    "dead,expected",
    [
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


# --- Fenetre orange du calendaire ---
#
# CHEMINS PAR LESQUELS L'INVARIANT « une tache calendaire previent AVANT
# l'echeance » peut etre viole, enumeres avant d'ecrire les cas :
#   (1) `compute_nominal_interval_days` ignore le type ......... COUVERT
#       C'etait le defaut : la fonction date de v0.10.0, `calendar` de
#       v0.37.0, elle tombait sur son `return 0` final.
#   (2) `months` vide ou absent ............................... COUVERT
#       Doit rendre 0 et non planter : la regle est incomplete, on ne
#       fabrique pas une fenetre a partir de rien.
#   (3) `compute_urgency_state` ignore le nominal qu'on lui passe  COUVERT
#       Teste de bout en bout : un ORANGE doit apparaitre AVANT le jour J.
#   (4) `uncertain` gagnerait une fenetre par effet de bord ..... COUVERT
#       Son 0 est deliberé et documente ; il doit le rester.


@pytest.mark.parametrize(
    "months, attendu",
    [
        ([5], 365),                      # annuel   -> fenetre 36 j
        ([3, 9], 182),                   # semestriel -> 18 j
        ([1, 4, 7, 10], 91),             # trimestriel -> 9 j
        (list(range(1, 13)), 30),        # mensuel  -> 3 j
    ],
)
def test_calendar_nominal_is_the_gap_between_occurrences(months, attendu):
    """Chemin (1) : le nominal d'une regle calendaire, c'est 365 / nb de mois."""
    regle = {"type": "calendar", "months": months, "day": 15, "grace_days": 0}
    assert compute_nominal_interval_days(regle) == attendu


def test_calendar_without_months_has_no_window_rather_than_crashing():
    """Chemin (2) : une regle incomplete ne fabrique pas de fenetre."""
    assert compute_nominal_interval_days({"type": "calendar", "months": []}) == 0
    assert compute_nominal_interval_days({"type": "calendar"}) == 0


@pytest.mark.parametrize(
    "regle, attendu",
    [
        ({"type": "calendar", "months": [5]}, 7),
        # A monthly calendar has a 3-day window: the cap does not widen it.
        ({"type": "calendar", "months": list(range(1, 13))}, 7),
        ({"type": "interval", "every_n_days": 365}, None),
        ({"type": "monthly_variable", "days_per_month": {"1": 60}}, None),
        (None, None),
    ],
)
def test_only_calendar_rules_cap_the_orange_window(regle, attendu):
    assert orange_window_cap_of(regle) == attendu


def test_cap_never_widens_a_short_window():
    """Monthly calendar: 3-day window, cap 7 -> still 3 days."""
    echeance = date(2026, 5, 15)
    assert compute_urgency_state(echeance, echeance - timedelta(days=4), 30, 0, 7) == (
        UrgencyState.GREEN
    )


def test_uncertain_keeps_its_deliberate_zero():
    """Chemin (4) : le 0 d'`uncertain` est un choix, il ne doit pas bouger."""
    assert compute_nominal_interval_days({"type": "uncertain"}) == 0


def test_a_yearly_task_warns_before_the_due_date():
    """Chemin (3), et c'est le defaut d'origine : un vaccin annuel passait de
    GREEN a ORANGE le JOUR de l'echeance, sans aucun « bientot du »."""
    regle = {"type": "calendar", "months": [5], "day": 15, "grace_days": 0}
    nominal = compute_nominal_interval_days(regle)
    cap = orange_window_cap_of(regle)
    echeance = date(2026, 5, 15)

    # floor(365 * 0.10) = 36 days, capped at 7 since 2026-09-30: 36 days of
    # orange on a vaccine was too early to be useful.
    assert cap == 7
    assert compute_urgency_state(echeance, echeance - timedelta(days=7), nominal, 0, cap) == (
        UrgencyState.ORANGE
    ), "une tache annuelle doit prevenir une semaine avant"
    assert compute_urgency_state(echeance, echeance - timedelta(days=8), nominal, 0, cap) == (
        UrgencyState.GREEN
    ), "le plafond de 7 j doit borner la fenetre de 36 j"
    # Without the cap the 10 % window is untouched (the cap is opt-in per rule).
    assert compute_urgency_state(echeance, echeance - timedelta(days=30), nominal) == (
        UrgencyState.ORANGE
    )
    # Apres, elle est en retard.
    assert compute_urgency_state(echeance, echeance + timedelta(days=1), nominal) == (
        UrgencyState.RED
    )
