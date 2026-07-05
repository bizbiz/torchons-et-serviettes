"""Service presence — helpers pour le home freeze v2.

Fournit les fonctions de comptage des jours absents depuis la table
presence_days. Utilisees par le calcul d'urgence (suggestions, projection)
pour decaler effective_today.
"""

from __future__ import annotations

import uuid
from datetime import date, datetime, timedelta
from typing import Iterable

from sqlalchemy import and_, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.category import Category
from app.models.completion import TaskCompletion
from app.models.foyer import Foyer
from app.models.presence_day import PresenceDay
from app.models.task import Task


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


async def compute_absent_days_for_tasks(
    session: AsyncSession,
    foyer_id: uuid.UUID,
    tasks: Iterable[Task],
    until_date: date | None = None,
) -> dict[uuid.UUID, int]:
    """Calcule en batch le nombre de jours absents par tache pour la
    propagation du home freeze v2 cote client.

    Pour chaque tache :
    - Renvoie 0 si foyer.home_freeze_enabled = False
    - Renvoie 0 si task.category.requires_home != True
    - Sinon, compte les jours non-presents entre la derniere completion (ou
      created_at si jamais faite) et `until_date` (exclus). Les jours sans
      ligne presence_day comptent comme absents (comportement freeze).

    Utilise pour exposer `absent_days_since_last_completion` sur les reponses
    GET /api/tasks afin que le frontend Android decale `today` localement
    dans TaskUrgencyCalculator.
    """
    if until_date is None:
        from datetime import datetime, timezone
        until_date = datetime.now(timezone.utc).date()

    task_list = list(tasks)
    result_map: dict[uuid.UUID, int] = {t.id: 0 for t in task_list}
    if not task_list:
        return result_map

    # 1. Foyer freeze activated ?
    foyer_r = await session.execute(
        select(Foyer.home_freeze_enabled).where(Foyer.id == foyer_id)
    )
    if not bool(foyer_r.scalar() or False):
        return result_map

    # 2. Categories requires_home : filter
    cat_ids = {t.category_id for t in task_list if t.category_id is not None}
    if not cat_ids:
        return result_map
    cat_r = await session.execute(
        select(Category.id, Category.requires_home).where(Category.id.in_(cat_ids))
    )
    requires_home_by_cat: dict[uuid.UUID, bool] = {
        row[0]: bool(row[1]) for row in cat_r.all()
    }
    eligible_tasks = [
        t for t in task_list
        if t.category_id is not None
        and requires_home_by_cat.get(t.category_id, False)
    ]
    if not eligible_tasks:
        return result_map

    # 3. Last completion date per task (fallback: created_at.date())
    task_ids = [t.id for t in eligible_tasks]
    comp_r = await session.execute(
        select(
            TaskCompletion.task_id,
            func.max(TaskCompletion.completed_at).label("last_at"),
        )
        .where(
            TaskCompletion.task_id.in_(task_ids),
            TaskCompletion.deleted_at.is_(None),
        )
        .group_by(TaskCompletion.task_id)
    )
    last_completion_by_task: dict[uuid.UUID, date] = {}
    for row in comp_r.all():
        last_completion_by_task[row[0]] = row[1].date()

    since_dates: dict[uuid.UUID, date] = {}
    for t in eligible_tasks:
        since = last_completion_by_task.get(t.id)
        if since is None:
            # Jamais faite : on part de created_at.date()
            since = t.created_at.date() if t.created_at else until_date
        since_dates[t.id] = since

    # 4. Batch absent count via get_absent_days_map
    absent_map = await get_absent_days_map(
        session, foyer_id, since_dates, until_date
    )
    for tid, n in absent_map.items():
        if n > 0:
            result_map[tid] = n

    return result_map


async def load_freeze_context(
    session: AsyncSession,
    foyer_id: uuid.UUID,
    tasks: Iterable[Task],
    last_completion_map: dict[uuid.UUID, datetime | None],
    today: date,
) -> tuple[bool, dict[uuid.UUID | None, bool], dict[uuid.UUID, int]]:
    """Charge le contexte home freeze v2 partage par /suggestions et /retard.

    Retourne (freeze_enabled, requires_home_map, absent_days_map) pour que les
    deux surfaces appliquent compute_effective_today de facon strictement
    identique. Centralise la logique auparavant dupliquee dans gamification et
    projection : c'est la source unique de la definition "en retard" cote foyer.

    `last_completion_map` = {task_id: derniere completion (datetime) ou None}.
    Le fallback pour une tache jamais faite est created_at.date().
    """
    task_list = list(tasks)
    foyer_result = await session.execute(select(Foyer).where(Foyer.id == foyer_id))
    foyer = foyer_result.scalar_one_or_none()
    freeze_enabled = foyer.home_freeze_enabled if foyer else False
    if not freeze_enabled:
        return False, {}, {}

    # Map category_id -> requires_home (uniquement les categories des taches).
    cat_ids = {t.category_id for t in task_list if t.category_id}
    requires_home_map: dict[uuid.UUID | None, bool] = {}
    if cat_ids:
        cat_result = await session.execute(
            select(Category.id, Category.requires_home).where(Category.id.in_(cat_ids))
        )
        requires_home_map = {row[0]: row[1] for row in cat_result.all()}

    # Jours absents en batch, uniquement pour les taches requires_home.
    since_dates: dict[uuid.UUID, date] = {}
    for t in task_list:
        if requires_home_map.get(t.category_id, False):
            lc = last_completion_map.get(t.id)
            since = lc.date() if lc else (t.created_at.date() if t.created_at else today)
            since_dates[t.id] = since

    absent_days_map: dict[uuid.UUID, int] = {}
    if since_dates:
        absent_days_map = await get_absent_days_map(session, foyer_id, since_dates, today)

    return freeze_enabled, requires_home_map, absent_days_map


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


async def seed_initial_presence(
    session: AsyncSession,
    foyer_id: uuid.UUID,
    days: int = 360,
    today: date | None = None,
) -> int:
    """Pre-remplit `presence_days` avec `days` jours confirmed_present a la
    creation d'un foyer (today inclus, en remontant).

    Sans ca, le Home-freeze v2 compte tout jour sans ligne presence comme
    absent ("freeze par defaut tant que non confirme present") : sur un
    foyer neuf qui n'a jamais fait de check-in WiFi, les taches
    requires_home avec historique backdate (import catalogue, completions
    seedees) paraissent jamais en retard (today decale en arriere). Friction
    vecue au dogfood 2026-05-16 ("Aspirateur dans 6j"). On part donc d'une
    presence confirmee par defaut sur l'annee ecoulee ; les jours futurs
    sans check-in restent geres par le freeze normal.

    Source dediee "foyer_init" (vs "manual"/"auto_wifi") pour l'audit.
    Idempotent : n'insere pas un (foyer, date) deja present.
    Retourne le nombre de lignes inserees.
    """
    if days <= 0:
        return 0
    ref = today or date.today()
    start = ref - timedelta(days=days - 1)
    existing = {
        row[0]
        for row in (
            await session.execute(
                select(PresenceDay.date).where(
                    PresenceDay.foyer_id == foyer_id,
                    PresenceDay.date >= start,
                    PresenceDay.date <= ref,
                )
            )
        ).all()
    }
    inserted = 0
    for offset in range(days):
        d = ref - timedelta(days=offset)
        if d in existing:
            continue
        session.add(
            PresenceDay(
                foyer_id=foyer_id,
                date=d,
                status="confirmed_present",
                source="foyer_init",
            )
        )
        inserted += 1
    return inserted
