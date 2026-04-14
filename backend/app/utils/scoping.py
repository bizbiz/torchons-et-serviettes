"""Helpers pour scoper les queries SQLAlchemy par foyer_id.

Usage :
    stmt = scoped(Task, current).where(Task.is_active.is_(True))
    result = await session.execute(stmt)

Scope = garantit WHERE Task.foyer_id = current["foyer_id"]. Utiliser
systematiquement sur les tables top-level (tasks, categories, rooms,
streaks, badges, fair_share_config, change_log) — c'est la barriere
d'isolation entre foyers.

Pour les tables dependantes (task_assignments, task_completions, etc.),
le scoping est transitif : on joint sur tasks et on filtre via Task.foyer_id.
"""

from __future__ import annotations

import uuid
from typing import Any

from sqlalchemy import Select, select


def scoped(model: Any, current: dict) -> Select:
    """Retourne un SELECT deja filtre sur model.foyer_id = current foyer.

    Le modele DOIT avoir une colonne foyer_id. Sinon, ValueError.
    """
    if not hasattr(model, "foyer_id"):
        raise ValueError(f"{model.__name__} n'a pas de colonne foyer_id — utilise un JOIN via tasks")
    foyer_id = current["foyer_id"]
    foyer_uuid = uuid.UUID(foyer_id) if isinstance(foyer_id, str) else foyer_id
    return select(model).where(model.foyer_id == foyer_uuid)


def current_foyer_uuid(current: dict) -> uuid.UUID:
    """Convertit le foyer_id du current user en UUID (pour les INSERT)."""
    foyer_id = current["foyer_id"]
    return uuid.UUID(foyer_id) if isinstance(foyer_id, str) else foyer_id
