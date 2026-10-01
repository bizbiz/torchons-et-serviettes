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

import calendar as _calendar
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


def _to_int(value: object, default: int | None = None) -> int | None:
    """Coerce une valeur numerique en int (tolere 7.0, '7', 7). None -> default."""
    if value is None:
        return default
    try:
        return int(round(float(value)))
    except (TypeError, ValueError):
        return default


def normalize_recurrence_rule(rule: dict | None) -> dict | None:
    """Force les champs numeriques d'une regle de recurrence en int.

    Le client Android (Gson) parse `every_n_days` comme un Int strict : une
    valeur float persistee en JSONB (ex. `7.0`, vu via /sync qui bypass
    Pydantic) casse le parsing -> recurrence ignoree -> urgence fausse
    ("dans 6j" au lieu de Rouge). On normalise donc a l'ecriture, cote
    serveur, quel que soit le chemin (REST ou /sync). Cf docs/cache-conventions.md.
    """
    if not isinstance(rule, dict):
        return rule
    out = dict(rule)
    rtype = out.get("type")
    if rtype == "interval" and "every_n_days" in out:
        out["every_n_days"] = _to_int(out["every_n_days"], 1)
    elif rtype == "calendar":
        # `months` : mois ou la tache devient due (1..12), au moins un.
        # `day`    : jour dans le mois, 1 par defaut.
        # `grace_days` : delai apres l'echeance avant le rouge. C'est ce qui
        #   exprime "mai-aout" — une SEULE occurrence, toleree tout l'ete —
        #   par opposition a "avril & octobre" qui en fait deux.
        months = out.get("months")
        if isinstance(months, list):
            clean = sorted({m for m in (_to_int(x) for x in months) if m and 1 <= m <= 12})
            out["months"] = clean or [1]
        else:
            out["months"] = [1]
        out["day"] = min(28, max(1, _to_int(out.get("day"), 1) or 1))
        out["grace_days"] = max(0, _to_int(out.get("grace_days"), 0) or 0)
    # weekdays / monthly / custom : types morts depuis v0.27, plus aucun calcul
    # d'echeance ne les traite. On les laissait passer la validation, ce qui
    # produisait une tache silencieusement inerte — ni rouge ni orange. On les
    # convertit desormais plutot que de les accepter tels quels.
    elif rtype == "monthly":
        # A fixed day every month IS a calendar rule. An `interval: 30` would
        # drift with each late completion — the defect `calendar` removes.
        # Same conversion as migration d4ec241658ca, which fixed the rows
        # stored before this branch existed.
        out = normalize_recurrence_rule({
            "type": "calendar",
            "months": list(range(1, 13)),
            "day": _to_int(out.get("day_of_month"), 1),
            "grace_days": 0,
        })
    elif rtype in ("weekdays", "custom"):
        out = {"type": "interval", "every_n_days": _legacy_interval(rtype, out)}
    elif rtype == "anniversary":
        # `every_n_months`: the period, 12 by default ("one year after the
        # first time"). Months rather than days so that "every 2 years" lands
        # on the same calendar day, leap years included.
        out = {"type": "anniversary", "every_n_months": _anniversary_months(out)}
    return out


#: `anniversary` period when none is given: one year.
ANNIVERSARY_DEFAULT_MONTHS = 12
#: Upper bound on the period (100 years), so a typo cannot make a loop of it.
ANNIVERSARY_MAX_MONTHS = 1200


def _anniversary_months(rule: dict) -> int:
    n = _to_int(rule.get("every_n_months"), ANNIVERSARY_DEFAULT_MONTHS)
    if n is None:
        n = ANNIVERSARY_DEFAULT_MONTHS
    return min(ANNIVERSARY_MAX_MONTHS, max(1, n))


#: Types a stored rule may carry. Anything else has no due-date computation on
#: either side (server or TaskUrgencyCalculator.kt): the task would be silently
#: inert. The REST schema (schemas/task.py) reads this set; the Kotlin mirror is
#: `RecurrenceTypes.LIVING`, compared by tests/test_recurrence_type_lists.py.
LIVING_RULE_TYPES = frozenset(
    {"interval", "monthly_variable", "uncertain", "calendar", "anniversary"}
)


def checked_recurrence_rule(rule: dict | None) -> dict | None:
    """Normalise, then refuse any type no read path knows.

    For /sync, which bypasses Pydantic. Raises ValueError: /sync turns it into a
    conflict on THIS op, so the client's queue is not blocked.
    """
    out = normalize_recurrence_rule(rule)
    if out is not None and (not isinstance(out, dict) or out.get("type") not in LIVING_RULE_TYPES):
        kind = out.get("type") if isinstance(out, dict) else type(out).__name__
        raise ValueError(f"unsupported recurrence type: {kind!r}")
    return out


def _legacy_interval(rtype: str, rule: dict) -> int:
    """Intervalle equivalent d'un type mort, pour ne rien perdre en migrant."""
    if rtype == "weekdays":
        days = [d for d in (_to_int(x) for x in rule.get("days") or []) if d is not None]
        return max(1, round(7 / len(days))) if days else 7
    every_n_weeks = _to_int(rule.get("every_n_weeks"), 1) or 1
    return max(1, every_n_weeks * 7)


def add_months(d: date, months: int) -> date:
    """`d` plus `months` calendar months, the day clamped to the month's end.

    Same semantics as `LocalDate.plusMonths` in Kotlin: 31 Jan + 1 -> 28/29 Feb.
    """
    y, m = divmod(d.month - 1 + months, 12)
    year, month = d.year + y, m + 1
    return date(year, month, min(d.day, _calendar.monthrange(year, month)[1]))


def next_anniversary_due(rule: dict, first: date, last: date) -> date:
    """Due date of an `anniversary` rule: anchored on the FIRST completion.

    Occurrences are `first + k * every_n_months` (k >= 1), each computed from
    the anchor so a 31st does not decay to the 28th. The due one is the
    earliest occurrence the LAST completion has not honoured; a completion
    honours an occurrence when made on or after its orange window opens, i.e.
    while the task is shown orange or red. Doing it late therefore never moves
    the next one: no drift, which `interval: 365` cannot promise.

    Pure, no clock. Mirrored by `nextAnniversaryDue` in TaskUrgencyCalculator.kt.
    """
    n = _anniversary_months(rule)
    window = timedelta(days=_orange_window_days(rule))
    # Start near the last completion rather than at k=1: a monthly rule
    # anchored ten years ago would otherwise walk 120 occurrences.
    months_between = (last.year - first.year) * 12 + (last.month - first.month)
    k = max(1, months_between // n)
    while k > 1 and add_months(first, (k - 1) * n) - window > last:
        k -= 1
    while add_months(first, k * n) - window <= last:
        k += 1
    return add_months(first, k * n)


def next_due(
    rule: dict | None,
    last_completed: datetime | None,
    first_completed: datetime | None = None,
) -> datetime | None:
    """Calcule la prochaine echeance d'une tache recurrente.

    `first_completed` only matters to `anniversary` (see compute_due_date).
    """
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

    elif rtype == "monthly_variable":
        # Recurrence variable par mois : days_per_month = {"1": 30, "7": 3, ...}.
        # Avec la v0.27, le format canonique est toujours 12 entrees, donc le
        # cas "mois manquant" ne devrait plus arriver — on garde quand meme un
        # fallback pour les anciens enregistrements eventuels.
        dpm = rule.get("days_per_month", {})
        current_month = str(now.month)
        n_days = dpm.get(current_month)
        if n_days is None:
            for offset in range(1, 13):
                m = ((now.month - 1 + offset) % 12) + 1
                if str(m) in dpm:
                    year = now.year + ((now.month - 1 + offset) // 12)
                    return datetime(year, m, 1, tzinfo=timezone.utc)
            return None
        if last_completed is None:
            return now
        return last_completed + timedelta(days=n_days)

    elif rtype == "calendar":
        # Ancrage CALENDAIRE : l'echeance ne depend pas de la derniere
        # completion, c'est toute sa raison d'etre. Faire ses impots en juin
        # ne deplace pas l'echeance de l'an prochain.
        months = rule.get("months") or [1]
        day = rule.get("day", 1)
        candidates = [
            datetime(y, m, day, tzinfo=timezone.utc)
            for y in (now.year - 1, now.year, now.year + 1)
            for m in months
        ]
        # La derniere echeance PASSEE fait foi tant qu'elle n'est pas honoree ;
        # une fois honoree, on vise la suivante. Sans ca, une tache faite le
        # jour meme repasserait due immediatement.
        past = [d for d in candidates if d <= now]
        future = [d for d in candidates if d > now]
        if not past:
            return min(future) if future else None
        latest_past = max(past)
        if last_completed is not None and last_completed >= latest_past:
            return min(future) if future else None
        return latest_past

    elif rtype == "anniversary":
        if last_completed is None:
            return now
        anchor = first_completed or last_completed
        due = next_anniversary_due(rule, anchor.date(), last_completed.date())
        return datetime(due.year, due.month, due.day, tzinfo=timezone.utc)

    # uncertain : pas d'echeance visible. weekdays/monthly/custom ont ete
    # supprimes en v0.27 (ils n'existaient plus en base mais le code mort
    # est nettoye pour reduire la surface de bug).
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

    if rtype in ("monthly_variable", "anniversary"):
        return one_day

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

    elif rtype == "monthly_variable":
        now = datetime.now(timezone.utc)
        dpm = rule.get("days_per_month", {})
        n = dpm.get(str(now.month))
        if n:
            return float(n) * 24.0
        return 30.44 * 24.0

    elif rtype == "calendar":
        # Intervalle nominal = l'annee divisee par le nombre d'echeances. Sert
        # a la fenetre orange et au taux de maintenance ; l'echeance reelle,
        # elle, ne s'en deduit pas (c'est le calendrier qui la fixe).
        months = rule.get("months") or [1]
        return (365.0 / max(1, len(months))) * 24.0

    elif rtype == "anniversary":
        return _anniversary_months(rule) * 365.0 / 12 * 24.0

    elif rtype == "uncertain":
        days = inferred_interval_days if inferred_interval_days is not None else float(COLD_START_INTERVAL_DAYS)
        return days * 24.0

    return float("inf")


# --- Nouveau modele d'urgence (v0.10.0) ---


def compute_nominal_interval_days(rule: dict | None) -> int:
    """Intervalle nominal entier en jours d'une regle de recurrence.

    Utilise pour dimensionner la fenetre orange "du / bientot du" :
    window = floor(nominal * 0.10).

    - interval : every_n_days
    - monthly_variable : moyenne des valeurs de days_per_month, fallback 30
    - calendar : 365 / nombre de mois occurrents (annuel -> 365, semestriel ->
      182, mensuel -> 30). Ajoute le 2026-09-03 : cette fonction date de
      v0.10.0, le type `calendar` de v0.37.0, et elle n'avait jamais ete
      etendue — il tombait sur le `return 0` final. Consequence mesuree : une
      tache annuelle passait de GREEN a ORANGE le JOUR de l'echeance et RED le
      lendemain, sans aucun "bientot du". C'etait un oubli, pas un choix : le
      `0` de `uncertain` est deliberé et documente, celui du calendaire ne
      l'etait nulle part.
    - uncertain : 0 -> pas de fenetre orange (etat reste GREEN par compute_urgency_state)
    - None (ponctuelle) : 0 -> window = 0, pas de zone orange
    """
    if rule is None:
        return 0

    rtype = rule.get("type")

    if rtype == "interval":
        return int(rule.get("every_n_days", 1))

    if rtype == "monthly_variable":
        dpm = rule.get("days_per_month", {})
        vals = [v for v in dpm.values() if isinstance(v, (int, float))]
        if vals:
            return max(1, round(sum(vals) / len(vals)))
        return 30

    if rtype == "calendar":
        # L'ecart entre deux occurrences : une regle a N mois revient N fois
        # par an. Un mois -> 365 j (fenetre 36), deux -> 182 (18), douze -> 30
        # (3). C'est la meme grandeur que `every_n_days` pour un interval,
        # donc la formule de fenetre s'y applique sans changement.
        months = rule.get("months") or []
        n = len([m for m in months if isinstance(m, int)])
        if n <= 0:
            return 0
        return max(1, round(365 / n))

    if rtype == "anniversary":
        # The period in days: 12 months -> 365, 24 -> 730, 1 -> 30.
        return max(1, round(_anniversary_months(rule) * 365 / 12))

    # uncertain : pas de fenetre orange — l'etat reste toujours GREEN.
    return 0


def compute_due_date(
    rule: dict | None,
    last_completed: datetime | None,
    created_at: datetime | None = None,
    first_completed: datetime | None = None,
) -> date | None:
    """Echeance (date) d'une tache dans le modele deterministe.

    Regles :
    - Jamais completee : due_date = created_at.date() (du immediatement,
      rouge des J+1, conformement a la decision design review).
    - Ponctuelle deja faite (rule None, last_completed set) : None
      (plus d'echeance, la tache est "reglee").
    - Recurrente : due_date = next_due(rule, last_completed).date()

    `first_completed` is the earliest LIVE completion (soft-deleted ones
    excluded): the anchor of an `anniversary` rule, ignored by every other
    type. Absent, the last completion stands in for it (one period after the
    last time) rather than leaving the task without a due date.

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

    nd = next_due(rule, last_completed, first_completed)
    if nd is None:
        return None
    return nd.date() if isinstance(nd, datetime) else nd


def grace_days_of(rule: dict | None) -> int:
    """Tolerance apres echeance declaree par la regle. 0 pour tout sauf
    `calendar`, ou elle exprime une fenetre ("acceptable jusqu'en aout")."""
    if not isinstance(rule, dict):
        return 0
    try:
        return max(0, int(rule.get("grace_days", 0) or 0))
    except (TypeError, ValueError):
        return 0


#: A date-anchored rule (`calendar`, `anniversary`) warns at most this many
#: days ahead. 10 % of a yearly gap is 36 days of orange, too early to be useful
#: on a vaccine (decided 2026-09-30). The cap bounds the WINDOW only: the
#: nominal interval still feeds the lateness ratio untouched. Mirrored in
#: TaskUrgencyCalculator.kt.
CALENDAR_ORANGE_WINDOW_CAP_DAYS = 7

#: Rule types whose orange window is capped. Same set as
#: `TaskUrgencyCalculator.orangeWindowCapOf`.
_CAPPED_WINDOW_TYPES = frozenset({"calendar", "anniversary"})


def orange_window_cap_of(rule: dict | None) -> int | None:
    """Upper bound on the orange window declared by the rule, None if unbounded."""
    if isinstance(rule, dict) and rule.get("type") in _CAPPED_WINDOW_TYPES:
        return CALENDAR_ORANGE_WINDOW_CAP_DAYS
    return None


def _orange_window_days(rule: dict | None) -> int:
    """Orange window in days, cap applied: the one compute_urgency_state uses."""
    window = max(0, int(math.floor(compute_nominal_interval_days(rule) * 0.10)))
    cap = orange_window_cap_of(rule)
    return window if cap is None else min(window, cap)


#: Rule types a frequency vote may adjust. The consensus always writes
#: `interval` or `monthly_variable`, so a vote on any other type would silently
#: replace it — a `calendar` rule would get back the drift it exists to remove.
#: An allowlist on purpose: `anniversary`, added after it, is non-votable
#: without anyone having had to remember it. Same set as
#: `RecurrenceTypes.VOTABLE` in Kotlin (tests/test_recurrence_type_lists.py).
VOTABLE_RULE_TYPES = frozenset({"interval", "monthly_variable"})


def is_votable(rule: dict | None) -> bool:
    return isinstance(rule, dict) and rule.get("type") in VOTABLE_RULE_TYPES


def compute_urgency_state(
    due_date: date | None,
    today: date,
    nominal_interval_days: int,
    grace_days: int = 0,
    window_cap_days: int | None = None,
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
    # `grace_days` ne repousse que le ROUGE : la tache est signalee a son
    # echeance (orange), mais n'est reprochee qu'apres la tolerance. C'est ce
    # qui exprime "chaudiere : due en mai, acceptable jusqu'a fin aout".
    if today > due_date + timedelta(days=max(0, grace_days)):
        return UrgencyState.RED
    window = max(0, int(math.floor(nominal_interval_days * 0.10)))
    if window_cap_days is not None:
        window = min(window, max(0, window_cap_days))
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
