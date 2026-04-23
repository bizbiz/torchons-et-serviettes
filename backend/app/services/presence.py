"""Service presence — helpers pour le home freeze v2.

Fournit les fonctions de comptage des jours absents depuis la table
presence_days. Utilisees par le calcul d'urgence (suggestions, projection)
pour decaler effective_today.
"""

from __future__ import annotations

import uuid
from datetime import date, timedelta

from sqlalchemy import and_, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.presence_day import PresenceDay


async def count_absent_days_since(
    session: AsyncSession,
    foyer_id: uuid.UUID,
    since_date: date,
    until_date: date,
) -> int:
    """Compte le nombre de jours confirmed_absent dans presence_days entre
    since_date (exclus) et until_date (exclus).

    Les jours sans ligne (incertains / non resolus) sont aussi comptes comme
    absents — c'est le comportement "freeze par defaut" tant que l'user n'a
    pas resolu.

    Utilisation typique :
    - since_date = date de la derniere completion de la tache
    - until_date = today
    """
    if since_date >= until_date:
        return 0

    start = since_date + timedelta(days=1)
    total_days = (until_date - start).days
    if total_days <= 0:
        return 0

    # Compter les jours confirmes presents dans la fenetre
    result = await session.execute(
        select(func.count(PresenceDay.id)).where(
            PresenceDay.foyer_id == foyer_id,
            PresenceDay.date >= start,
            PresenceDay.date < until_date,
            PresenceDay.status == "confirmed_present",
        )
    )
    present_count = result.scalar() or 0

    # Les jours non-presents (absents confirmes + jours sans ligne) = total - presents
    return total_days - present_count


async def get_absent_days_map(
    session: AsyncSession,
    foyer_id: uuid.UUID,
    since_dates: dict[uuid.UUID, date],
    until_date: date,
) -> dict[uuid.UUID, int]:
    """Version batch : compte les jours absents pour plusieurs taches.

    since_dates = {task_id: date_derniere_completion}
    Retourne {task_id: nb_jours_absents}

    Optimise pour eviter N queries — charge tous les presence_days pertinents
    en une seule requete.
    """
    if not since_dates:
        return {}

    # Trouver la plus ancienne date de reference
    min_date = min(since_dates.values())
    start = min_date + timedelta(days=1)

    if start >= until_date:
        return {tid: 0 for tid in since_dates}

    # Charger tous les jours presents dans la fenetre globale
    result = await session.execute(
        select(PresenceDay.date).where(
            PresenceDay.foyer_id == foyer_id,
            PresenceDay.date >= start,
            PresenceDay.date < until_date,
            PresenceDay.status == "confirmed_present",
        )
    )
    present_dates: set[date] = {row[0] for row in result.all()}

    # Pour chaque tache, compter les jours non-presents depuis sa completion
    absent_map = {}
    for task_id, since in since_dates.items():
        task_start = since + timedelta(days=1)
        if task_start >= until_date:
            absent_map[task_id] = 0
            continue
        total = (until_date - task_start).days
        present = sum(1 for d in present_dates if d >= task_start)
        absent_map[task_id] = total - present

    return absent_map


async def get_absent_dates(
    session: AsyncSession,
    foyer_id: uuid.UUID,
    since_date: date,
    until_date: date,
) -> set[date]:
    """Retourne les dates explicitement confirmed_absent (foyer-level) dans
    la fenetre [since_date, until_date] inclusive.

    Contrairement a count_absent_days_since / get_absent_days_map, les jours
    sans ligne presence_day ne sont PAS consideres absents — sert au gel
    des streaks ou un jour incertain doit casser le streak comme un jour
    ordinaire sans completion (incite l'user a resoudre via le dialog).
    """
    if since_date > until_date:
        return set()
    result = await session.execute(
        select(PresenceDay.date).where(
            PresenceDay.foyer_id == foyer_id,
            PresenceDay.date >= since_date,
            PresenceDay.date <= until_date,
            PresenceDay.status == "confirmed_absent",
        )
    )
    return {row[0] for row in result.all()}
