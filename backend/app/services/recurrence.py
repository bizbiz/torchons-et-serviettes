"""Moteur de recurrence — calcul next_due, is_overdue, due_date, urgency.

Depuis v0.10.0 : refonte du modele freshness (decroissance exponentielle)
vers un modele deterministe base sur due_date. Les fonctions de bas niveau
(next_due, is_overdue, interval_hours) sont conservees — elles sont encore
utilisees par projection.py (/api/retard, /api/maintenance-rate). Les
nouvelles fonctions (compute_due_date, compute_nominal_interval_days,
compute_urgency_state, compute_countdown_label) sont le point d'entree
officiel du nouveau modele.
"""

from __future__ import annotations

import math
from datetime import date, datetime, timedelta, timezone

from app.models.enums import UrgencyState


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
    """Verifie si une tache recurrente est en retard (precision datetime).

    NOTE : conserve pour projection.py (endpoint /api/retard). Pour le nouveau
    modele d'urgence utilise en liste/home, preferer compute_urgency_state
    qui travaille a la granularite jour.
    """
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
    """Intervalle nominal en heures d'une recurrence.

    Conserve pour projection.py (/api/maintenance-rate qui somme
    task_minutes / (interval_hours/24) sur les taches recurrentes).
    """
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


# --- Nouveau modele d'urgence (v0.10.0) ---


def compute_nominal_interval_days(rule: dict | None) -> int:
    """Intervalle nominal entier en jours d'une regle de recurrence.

    Utilise pour dimensionner la fenetre orange "du / bientot du" :
    window = floor(nominal * 0.10).

    Pour les recurrences non-interval, on renvoie une constante par tache
    (pas de gap reel entre occurrences), plus previsible pour l'utilisateur.

    - interval : every_n_days
    - weekdays : max(1, round(7 / len(days)))
    - monthly : 30
    - custom : every_n_weeks * 7
    - monthly_variable : moyenne des valeurs de days_per_month, fallback 30
    - None (ponctuelle) : 0 -> window = 0, pas de zone orange
    """
    if rule is None:
        return 0

    rtype = rule.get("type")

    if rtype == "interval":
        return int(rule.get("every_n_days", 1))

    if rtype == "weekdays":
        days = rule.get("days", [])
        if not days:
            return 0
        return max(1, round(7 / len(days)))

    if rtype == "monthly":
        return 30

    if rtype == "custom":
        return int(rule.get("every_n_weeks", 1)) * 7

    if rtype == "monthly_variable":
        dpm = rule.get("days_per_month", {})
        vals = [v for v in dpm.values() if isinstance(v, (int, float))]
        if vals:
            return max(1, round(sum(vals) / len(vals)))
        return 30

    return 0


def compute_due_date(
    rule: dict | None,
    last_completed: datetime | None,
    created_at: datetime | None = None,
) -> date | None:
    """Echeance (date) d'une tache dans le modele deterministe.

    Regles :
    - Jamais completee : due_date = created_at.date() (du immediatement,
      rouge des J+1, conformement a la decision /grill-me).
    - Ponctuelle deja faite (rule None, last_completed set) : None
      (plus d'echeance, la tache est "reglee").
    - Recurrente : due_date = next_due(rule, last_completed).date()

    Return None signifie "pas d'echeance a surveiller" (ponctuelle faite).
    """
    if last_completed is None:
        if created_at is None:
            return None
        if isinstance(created_at, datetime):
            return created_at.date()
        return created_at

    if rule is None:
        return None

    nd = next_due(rule, last_completed)
    if nd is None:
        return None
    return nd.date() if isinstance(nd, datetime) else nd


def compute_urgency_state(
    due_date: date | None,
    today: date,
    nominal_interval_days: int,
) -> UrgencyState:
    """Classifie une tache en GREEN / ORANGE / RED selon today <-> due_date.

    Fenetre orange = floor(nominal_interval_days * 0.10) jours avant echeance.
    Exemples :
    - interval 2 j : window = 0 -> orange uniquement le jour due_date
    - interval 7 j : window = 0 -> idem (0.7 -> floor = 0)
    - interval 30 j : window = 3 -> orange a J-3, J-2, J-1, J
    - interval 60 j : window = 6 -> orange a J-6 ... J

    due_date None (ponctuelle deja faite) -> GREEN (pas a afficher).
    """
    if due_date is None:
        return UrgencyState.GREEN
    if today > due_date:
        return UrgencyState.RED
    window = max(0, int(math.floor(nominal_interval_days * 0.10)))
    if today >= due_date - timedelta(days=window):
        return UrgencyState.ORANGE
    return UrgencyState.GREEN


def compute_days_until_due(due_date: date | None, today: date) -> int | None:
    """Nombre de jours jusqu'a l'echeance (negatif si en retard).

    None si la tache n'a pas d'echeance (ponctuelle deja faite).
    """
    if due_date is None:
        return None
    return (due_date - today).days


def compute_countdown_label(due_date: date | None, today: date) -> str:
    """Libelle humain FR du countdown ("dans 3 j", "a faire aujourd'hui", etc.)."""
    if due_date is None:
        return ""
    delta = (due_date - today).days
    if delta > 1:
        return f"dans {delta} j"
    if delta == 1:
        return "demain"
    if delta == 0:
        return "a faire aujourd'hui"
    n = -delta
    return f"en retard de {n} j"


def compute_effective_now(
    now: datetime,
    *,
    task_requires_home: bool = False,
    home_freeze_enabled: bool = False,
    foyer_occupied: bool = True,
    foyer_last_occupied_at: datetime | None = None,
) -> datetime:
    """Applique le home freeze (v0.7.4).

    Si la tache necessite d'etre a la maison ET que le freeze est active ET
    que le foyer est non occupe depuis plus de 24h, on fige "now" a
    last_occupied + 24h. Les premieres 24h d'absence comptent normalement,
    au-dela le compteur est gele.

    Utilise pour calculer un `effective_today` qui ne bouge pas tant que le
    foyer est vide.
    """
    if (
        task_requires_home
        and home_freeze_enabled
        and not foyer_occupied
        and foyer_last_occupied_at is not None
    ):
        last_occupied = foyer_last_occupied_at
        if last_occupied.tzinfo is None:
            last_occupied = last_occupied.replace(tzinfo=timezone.utc)
        frozen_at = last_occupied + timedelta(hours=24)
        if now > frozen_at:
            return frozen_at
    return now
