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
from statistics import median

from app.models.enums import UrgencyState


# --- Constantes recurrence "uncertain" (v0.14) ---

# Intervalle par defaut tant qu'on a moins de 2 completions pour une tache
# uncertain. Choisi sur la base du cas canonique "lessive" (~7j).
COLD_START_INTERVAL_DAYS = 7

# K = nombre de cycles de l'intervalle infere dans la fenetre d'observation.
# Fenetre = K * inferred_interval. Permet une adaptation auto aux changements
# de cadence. Voir compute_inferred_interval_days().
INFERENCE_CYCLES = 5

# Plafond du score de ranking d/i pour eviter qu'une tache oubliee depuis
# 6 mois ecrase tout. Au-dela de 2x la cadence, toutes les taches sont
# egalement "tres en retard", le tie-break par d resoud.
UNCERTAIN_SCORE_CAP = 2.0


def is_uncertain(rule: dict | None) -> bool:
    """Retourne True si la regle de recurrence est de type 'uncertain'."""
    return isinstance(rule, dict) and rule.get("type") == "uncertain"


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


def min_completion_gap_seconds(
    rule: dict | None,
    inferred_interval_days: float | None = None,
) -> int:
    """Delai minimum (en secondes) entre deux completions consecutives.

    Derive de la recurrence :
    - Recurrence >= 7 jours d'intervalle effectif : 24h (meme journee bloquee)
    - Recurrence plus frequente ou absente : 1h (meme heure bloquee)

    Pour une recurrence 'uncertain', on utilise inferred_interval_days s'il
    est connu (>= 7j -> 24h, sinon 1h). Sinon fallback 1h.

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

    if rtype == "uncertain":
        if inferred_interval_days is not None and inferred_interval_days >= 7:
            return one_day
        return one_hour

    return one_hour


def interval_hours(
    rule: dict | None,
    inferred_interval_days: float | None = None,
) -> float:
    """Intervalle nominal en heures d'une recurrence.

    Conserve pour projection.py (/api/maintenance-rate qui somme
    task_minutes / (interval_hours/24) sur les taches recurrentes).

    Pour une recurrence 'uncertain', utilise inferred_interval_days s'il est
    connu, sinon retombe sur COLD_START_INTERVAL_DAYS (cohérent avec Q4).
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

    elif rtype == "uncertain":
        days = inferred_interval_days if inferred_interval_days is not None else float(COLD_START_INTERVAL_DAYS)
        return days * 24.0

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

    # uncertain : pas de fenetre orange — l'etat reste toujours GREEN (Q2).
    # Rendu via compute_due_date -> None, donc compute_urgency_state -> GREEN.
    if rtype == "uncertain":
        return 0

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
    # uncertain : aucune echeance visible (Q2). L'etat reste GREEN, le
    # classement passe par le bucket dedie "Sans cadence" (Q1, Q3, Q8).
    if is_uncertain(rule):
        return None

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


def compute_uncertain_countdown_label(
    last_completed: datetime | None,
    today: date,
) -> str:
    """Libelle factuel FR pour une tache 'uncertain' (Q9).

    Retourne :
    - "Pas encore faite" si jamais completee
    - "Fait aujourd'hui" si completee aujourd'hui
    - "Fait hier" si completee hier
    - "Fait il y a N j" sinon
    """
    if last_completed is None:
        return "Pas encore faite"
    delta = (today - last_completed.date()).days
    if delta <= 0:
        return "Fait aujourd'hui"
    if delta == 1:
        return "Fait hier"
    return f"Fait il y a {delta} j"


def compute_inferred_interval_days(
    completion_times: list[datetime],
    absent_days_per_gap: list[int] | None = None,
) -> float | None:
    """Infere la cadence effective d'une tache 'uncertain' a partir de son
    historique de completions (Q5, Q6.1 gamma, Q6.2 freeze).

    Algorithme (2 passes) :
      1. Calcule les intervalles bruts entre completions consecutives triees
         chronologiquement (en jours calendaires).
      2. Si absent_days_per_gap est fourni (freeze respecte, Q6.2) : soustrait
         les jours absents de chaque intervalle. Les intervalles effectifs
         <= 0 sont clampes a une petite valeur pour ne pas fausser la mediane.
      3. Pass 1 : mediane sur tous les intervalles effectifs.
      4. Pass 2 : ne garde que les intervalles dont la completion de FIN est
         posterieure a (now - INFERENCE_CYCLES * mediane_pass1). Recalcule
         la mediane. Si la fenetre est vide, garde la pass 1.

    Retourne None si moins de 2 completions (cold start, Q4). Le caller
    basculera alors sur COLD_START_INTERVAL_DAYS pour le ranking.

    Parametres :
    - completion_times : datetimes de completion, dans N'IMPORTE QUEL ordre
      (on trie ici).
    - absent_days_per_gap : pour chaque paire consecutive (i, i+1) apres tri,
      nombre de jours absents. Longueur = len(completions) - 1. None si le
      freeze ne s'applique pas (tache non requires_home ou freeze off).
    """
    if not completion_times or len(completion_times) < 2:
        return None

    ordered = sorted(completion_times)
    gaps_days: list[float] = []
    ends: list[datetime] = []
    for i in range(1, len(ordered)):
        start = ordered[i - 1]
        end = ordered[i]
        raw = (end - start).total_seconds() / 86400.0
        absent = 0
        if absent_days_per_gap is not None and i - 1 < len(absent_days_per_gap):
            absent = max(0, absent_days_per_gap[i - 1])
        effective = max(0.1, raw - absent)
        gaps_days.append(effective)
        ends.append(end)

    if not gaps_days:
        return None

    pass1 = float(median(gaps_days))

    now = datetime.now(timezone.utc)
    cutoff = now - timedelta(days=INFERENCE_CYCLES * pass1)
    windowed = [g for g, end in zip(gaps_days, ends) if end >= cutoff]
    if not windowed:
        return pass1
    return float(median(windowed))


def compute_effective_today(
    today: date,
    *,
    task_requires_home: bool = False,
    home_freeze_enabled: bool = False,
    absent_days: int = 0,
) -> date:
    """Calcule la date effective en tenant compte du home freeze (v2).

    Si la tache necessite d'etre a la maison ET que le freeze est active,
    on recule `today` du nombre de jours confirmes absents dans la table
    presence_days depuis la derniere completion de la tache. Le temps ne
    s'ecoule pas pendant les jours d'absence.

    Exemple : today = 17 avril, 3 jours absents depuis derniere completion
    -> effective_today = 14 avril. Si due_date = 16 avril, la tache n'est
    pas encore en retard (14 < 16).

    Le comptage des jours absents (absent_days) est fait par le caller via
    count_absent_days_since() qui query la table presence_days.
    """
    if task_requires_home and home_freeze_enabled and absent_days > 0:
        return today - timedelta(days=absent_days)
    return today
