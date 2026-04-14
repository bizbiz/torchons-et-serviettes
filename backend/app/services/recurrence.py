"""Moteur de recurrence — calcul next_due, is_overdue, interval_hours."""

from __future__ import annotations

import math
from datetime import date, datetime, timedelta, timezone

from app.models.enums import TaskTolerance


# Multiplicateur applique a l'intervalle nominal pour obtenir la demi-vie
# de freshness selon la tolerance choisie pour la tache.
_TOLERANCE_MULTIPLIER: dict[TaskTolerance, float] = {
    TaskTolerance.STRICT: 0.5,
    TaskTolerance.NORMAL: 1.0,
    TaskTolerance.SOUPLE: 2.0,
}


def next_due(rule: dict | None, last_completed: datetime | None) -> datetime | None:
    """Calcule la prochaine echeance d'une tache recurrente."""
    if rule is None:
        return None  # tache ponctuelle

    now = datetime.now(timezone.utc)
    anchor = last_completed or now

    rtype = rule.get("type")

    if rtype == "interval":
        n = rule.get("every_n_days", 1)
        if last_completed is None:
            return now
        return last_completed + timedelta(days=n)

    elif rtype == "weekdays":
        days = rule.get("days", [])  # ISO: 1=lundi ... 7=dimanche
        if not days:
            return None
        start = (anchor + timedelta(days=1)).date() if last_completed else anchor.date()
        for offset in range(8):  # max 7 jours + 1
            candidate = start + timedelta(days=offset)
            if candidate.isoweekday() in days:
                return datetime(candidate.year, candidate.month, candidate.day, tzinfo=timezone.utc)
        return None

    elif rtype == "monthly":
        dom = rule.get("day_of_month", 1)
        ref = anchor.date() if last_completed else anchor.date() - timedelta(days=1)
        # Mois suivant si on a deja passe le jour ce mois-ci
        year, month = ref.year, ref.month
        if ref.day >= dom:
            month += 1
            if month > 12:
                month, year = 1, year + 1
        # Clamp au dernier jour du mois si dom > nb jours
        import calendar
        max_day = calendar.monthrange(year, month)[1]
        day = min(dom, max_day)
        return datetime(year, month, day, tzinfo=timezone.utc)

    elif rtype == "custom":
        n_weeks = rule.get("every_n_weeks", 1)
        day = rule.get("day", 1)  # ISO weekday
        if last_completed is None:
            return now
        start = last_completed.date() + timedelta(days=1)
        # Avancer de n_weeks semaines depuis la derniere completion, trouver le bon jour
        target = last_completed.date() + timedelta(weeks=n_weeks)
        # Ajuster au jour de la semaine voulu
        diff = (day - target.isoweekday()) % 7
        target = target + timedelta(days=diff)
        return datetime(target.year, target.month, target.day, tzinfo=timezone.utc)

    elif rtype == "monthly_variable":
        # Recurrence variable par mois : days_per_month = {"1": 30, "7": 3, ...}
        dpm = rule.get("days_per_month", {})
        current_month = str(now.month)
        n_days = dpm.get(current_month)
        if n_days is None:
            # Mois inactif — chercher le prochain mois actif
            for offset in range(1, 13):
                m = ((now.month - 1 + offset) % 12) + 1
                if str(m) in dpm:
                    year = now.year + ((now.month - 1 + offset) // 12)
                    return datetime(year, m, 1, tzinfo=timezone.utc)
            return None
        if last_completed is None:
            return now
        return last_completed + timedelta(days=n_days)

    return None


def is_overdue(rule: dict | None, last_completed: datetime | None) -> bool:
    """Verifie si une tache recurrente est en retard."""
    due = next_due(rule, last_completed)
    if due is None:
        return False
    return due <= datetime.now(timezone.utc)


def min_completion_gap_seconds(rule: dict | None) -> int:
    """Delai minimum (en secondes) entre deux completions consecutives.

    Derive de la recurrence :
    - Recurrence >= 7 jours d'intervalle effectif : 24h (meme journee bloquee)
    - Recurrence plus frequente ou absente : 1h (meme heure bloquee)

    Utilise pour empecher les doubles validations accidentelles.
    """
    one_hour = 3600
    one_day = 86400

    if rule is None:
        return one_hour

    rtype = rule.get("type")

    if rtype == "interval":
        every_n_days = rule.get("every_n_days", 1)
        return one_day if every_n_days >= 7 else one_hour

    if rtype == "weekdays":
        days = rule.get("days", [])
        # 1 jour/semaine ou moins = 24h, sinon 1h
        return one_day if len(days) <= 1 else one_hour

    if rtype == "monthly" or rtype == "monthly_variable":
        return one_day

    if rtype == "custom":
        every_n_weeks = rule.get("every_n_weeks", 1)
        return one_day if every_n_weeks >= 1 else one_hour

    return one_hour


def interval_hours(rule: dict | None) -> float:
    """Calcule l'intervalle nominal en heures (pour freshness)."""
    if rule is None:
        return float("inf")

    rtype = rule.get("type")

    if rtype == "interval":
        return rule.get("every_n_days", 1) * 24.0

    elif rtype == "weekdays":
        days = rule.get("days", [])
        if not days:
            return float("inf")
        return (7.0 / len(days)) * 24.0

    elif rtype == "monthly":
        return 30.44 * 24.0  # moyenne mois

    elif rtype == "custom":
        return rule.get("every_n_weeks", 1) * 7.0 * 24.0

    elif rtype == "monthly_variable":
        now = datetime.now(timezone.utc)
        dpm = rule.get("days_per_month", {})
        n = dpm.get(str(now.month))
        if n:
            return float(n) * 24.0
        return 30.44 * 24.0

    return float("inf")


def compute_freshness(
    rule: dict | None,
    last_completed: datetime | None,
    created_at: datetime | None = None,
    tolerance: TaskTolerance = TaskTolerance.NORMAL,
    *,
    foyer_occupied: bool = True,
    foyer_last_occupied_at: datetime | None = None,
    task_requires_home: bool = False,
    home_freeze_enabled: bool = False,
) -> float:
    """Calcule le score de freshness (0-100). Decroissance exponentielle.

    La demi-vie est derivee de l'intervalle nominal de la recurrence,
    multipliee par le facteur de tolerance (STRICT x0.5, NORMAL x1, SOUPLE x2).

    Home freeze (v0.7.4) : si `task_requires_home=True` ET `home_freeze_enabled=True`
    ET le foyer est non-occupe ET l'absence depasse 24h, on cap le "now"
    effectif a `last_occupied_at + 24h`. Consequence : les premieres 24h
    d'absence comptent normalement, au-dela le compteur est fige.
    """
    base = interval_hours(rule)
    if base == float("inf") or base <= 0:
        # Ponctuelle : 0% tant que non faite, 100% une fois faite.
        return 0.0 if last_completed is None else 100.0
    half_life = base * _TOLERANCE_MULTIPLIER[tolerance]
    if half_life <= 0:
        return 0.0 if last_completed is None else 100.0

    now = datetime.now(timezone.utc)

    # Home freeze : decale "now" a un "effective_now" si conditions remplies.
    effective_now = now
    if (
        task_requires_home
        and home_freeze_enabled
        and not foyer_occupied
        and foyer_last_occupied_at is not None
    ):
        last_occupied = foyer_last_occupied_at
        if last_occupied.tzinfo is None:
            last_occupied = last_occupied.replace(tzinfo=timezone.utc)
        # Grace period 24h : les premieres 24h d'absence comptent normalement,
        # au-dela on fige a (last_occupied + 24h).
        frozen_at = last_occupied + timedelta(hours=24)
        if now > frozen_at:
            effective_now = frozen_at

    # Jamais completee = fraicheur 0% (a faire)
    if last_completed is None:
        return 0.0

    reference = last_completed
    if reference.tzinfo is None:
        reference = reference.replace(tzinfo=timezone.utc)

    hours_since = (effective_now - reference).total_seconds() / 3600.0
    if hours_since <= 0:
        return 100.0

    return max(0.0, min(100.0, 100.0 * math.exp(-0.693 * hours_since / half_life)))
