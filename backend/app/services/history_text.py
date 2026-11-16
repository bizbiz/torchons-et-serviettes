"""History event descriptions, per locale (moved out of api/stats.py).

Completion lines carry their points only for a reader who sees points: the
caller passes `points_suffix` built by `points_suffix()`, empty otherwise.
"""

from __future__ import annotations


# Templates de description d'evenements historique, par locale.
_HISTORY_TEMPLATES: dict[str, dict[str, str]] = {
    "fr": {
        "completion_solo": "{who} a complété : {title}{pts}",
        "completion_collective": "{joined} ont fait : {title}{pts}",
        "points_solo": " (+{points} pts)",
        "points_collective": " (+{points} pts chacun)",
        "affinity": "{who} a noté l'affinité de '{title}' à {score}/10",
        "effort": "{who} a estimé l'effort de '{title}' à {minutes}",
        "task_created": "{who} a créé la tâche '{title}'",
        "task_deleted": "{who} a supprimé la tâche '{title}'",
        "task_updated": "{who} a modifié la tâche '{title}' ({fields}){detail}",
        "completion_action_update": "modifié",
        "completion_action_delete": "supprimé",
        "completion_edited_or_deleted": "{who} a {action} une complétion de '{title}'",
        "category_action_insert": "créé",
        "category_action_update": "modifié",
        "category_action_delete": "supprimé",
        "category_changed": "{who} a {action} une catégorie",
        "task_deleted_suffix": " (supprimée)",
        "recurrence_interval_format": "tous les {n}j",
        "recurrence_monthly_variable": "variable selon le mois",
        "recurrence_uncertain": "irrégulière",
        "recurrence_calendar": "à date fixe",
        "recurrence_anniversary_years_format": "tous les {n} an(s) après la 1re fois",
        "recurrence_anniversary_months_format": "tous les {n} mois après la 1re fois",
        "recurrence_none": "ponctuelle",
    },
    "en": {
        "completion_solo": "{who} completed: {title}{pts}",
        "completion_collective": "{joined} did: {title}{pts}",
        "points_solo": " (+{points} pts)",
        "points_collective": " (+{points} pts each)",
        "affinity": "{who} rated affinity of '{title}' at {score}/10",
        "effort": "{who} estimated effort of '{title}' at {minutes}",
        "task_created": "{who} created task '{title}'",
        "task_deleted": "{who} deleted task '{title}'",
        "task_updated": "{who} edited task '{title}' ({fields}){detail}",
        "completion_action_update": "edited",
        "completion_action_delete": "deleted",
        "completion_edited_or_deleted": "{who} {action} a completion of '{title}'",
        "category_action_insert": "created",
        "category_action_update": "edited",
        "category_action_delete": "deleted",
        "category_changed": "{who} {action} a category",
        "task_deleted_suffix": " (deleted)",
        "recurrence_interval_format": "every {n}d",
        "recurrence_monthly_variable": "varies by month",
        "recurrence_uncertain": "irregular",
        "recurrence_calendar": "on a fixed date",
        "recurrence_anniversary_years_format": "every {n} year(s) after the first time",
        "recurrence_anniversary_months_format": "every {n} month(s) after the first time",
        "recurrence_none": "one-shot",
    },
}


def _t(locale: str, key: str, **kwargs) -> str:
    """Format un template d'historique dans la locale demandee (fallback FR)."""
    bucket = _HISTORY_TEMPLATES.get(locale) or _HISTORY_TEMPLATES["fr"]
    template = bucket.get(key) or _HISTORY_TEMPLATES["fr"].get(key) or key
    try:
        return template.format(**kwargs)
    except (KeyError, IndexError):
        return template


def points_suffix(locale: str, key: str, points: int, show: bool) -> str:
    """" (+3 pts)" for a reader who sees points, "" otherwise.

    `key` is `points_solo` or `points_collective`.
    """
    return _t(locale, key, points=points) if show else ""
