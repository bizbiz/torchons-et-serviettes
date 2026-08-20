"""Routes projection — magic buttons v0.8.0.

Endpoints pour le cockpit de prevision du rattrapage du retard :

- GET /api/retard : somme des minutes en retard (tache overdue × weight × 5min),
  nombre de taches en retard, liste des task_ids. Scope foyer actif.
- GET /api/pace : rythme moyen 14 derniers jours par user du foyer actif,
  exprime en minutes/jour et en taches/jour. Egalement : taches completees
  aujourd'hui par user, objectif perso actuel.
- GET /api/maintenance-rate : taux de "maintenance" (min/j combine pour
  maintenir zero retard en regime permanent = somme sur les taches recurrentes
  de task.weight * 5 / every_n_days_equivalent).
- GET /api/objective : objectif perso de l'user courant + preference d'unite
  d'affichage (min ou tasks).
- PUT /api/objective : set l'objectif perso de l'user courant (via le popup
  de simulation "Juste pour moi").

Les ponctuelles (pas de recurrence_rule) sont exclues du retard et de la
maintenance rate : elles n'ont pas de notion d'intervalle, donc pas
d'echeance, donc pas de retard.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.utils.errors import LocalizedHTTPException
from app.models.completion import TaskCompletion
from app.models.task import Task
from app.models.user import User
from app.models.user_foyer import UserFoyer
from app.models.enums import UrgencyState
from app.services.presence import load_freeze_context
from app.services.recurrence import (
    compute_due_date,
    compute_effective_today,
    compute_nominal_interval_days,
    compute_urgency_state,
    interval_hours,
    is_uncertain,
    grace_days_of,
)
from app.utils.scoping import current_foyer_uuid

router = APIRouter(prefix="/api", tags=["projection"])


# --- Constantes ---

# 1 point d'effort = 5 minutes (convention gamification P3, cf task_effort.py).
MINUTES_PER_POINT = 5

# Fenetre de mesure du rythme moyen : 14 jours glissants (decision grille
# design review v0.8.0 — lissage des week-ends + reactivite correcte).
PACE_WINDOW_DAYS = 14


# --- Helpers math ---


async def _fetch_foyer_tasks_with_last_completion(
    session: AsyncSession, foyer_id: uuid.UUID
) -> list[tuple[Task, datetime | None]]:
    """Charge toutes les taches actives du foyer + leur derniere completion.

    Retourne une liste de tuples (Task, last_completed_at | None). Fait une
    seule query groupee pour eviter le N+1 sur les last completions.
    """
    tasks_result = await session.execute(
        select(Task).where(
            Task.foyer_id == foyer_id,
            Task.deleted_at.is_(None),
            Task.is_active.is_(True),
            Task.is_archived.is_(False),
        )
    )
    tasks = tasks_result.scalars().all()
    if not tasks:
        return []

    task_ids = [t.id for t in tasks]
    last_result = await session.execute(
        select(
            TaskCompletion.task_id,
            func.max(TaskCompletion.completed_at),
        )
        .where(
            TaskCompletion.task_id.in_(task_ids),
            TaskCompletion.deleted_at.is_(None),
        )
        .group_by(TaskCompletion.task_id)
    )
    last_map: dict[uuid.UUID, datetime] = {row[0]: row[1] for row in last_result.all()}

    return [(t, last_map.get(t.id)) for t in tasks]


def _task_minutes(task: Task) -> int:
    """Minutes d'effort pour une tache (weight x 5 min, cf MINUTES_PER_POINT)."""
    return max(1, task.weight or 1) * MINUTES_PER_POINT


def _compute_maintenance_minutes_per_day(
    tasks_with_last: list[tuple[Task, datetime | None]]
) -> float:
    """Minutes/jour combinees necessaires pour maintenir zero retard.

    Pour chaque tache recurrente, on calcule `task_minutes / jours_par_cycle`
    ou `jours_par_cycle = interval_hours(rule) / 24`. On somme sur toutes les
    taches recurrentes. Les ponctuelles sont exclues (pas d'intervalle).

    Pour les taches 'uncertain' (v0.14) : interval_hours utilise
    inferred_interval_days persiste sur la tache (ou COLD_START_INTERVAL_DAYS
    en fallback). Elles comptent donc dans la maintenance-rate (Q10).
    """
    total = 0.0
    for task, _ in tasks_with_last:
        if task.recurrence_rule is None:
            continue
        hours = interval_hours(task.recurrence_rule, task.inferred_interval_days)
        if not (hours > 0 and hours != float("inf")):
            continue
        days = hours / 24.0
        total += _task_minutes(task) / days
    return total


# --- Schemas ---


class RetardTask(BaseModel):
    task_id: uuid.UUID
    title: str
    minutes: int
    overdue_since: datetime | None  # next_due computed


class RetardResponse(BaseModel):
    total_minutes: int
    task_count: int
    tasks: list[RetardTask]


class UserPace(BaseModel):
    user_id: uuid.UUID
    display_name: str
    minutes_per_day_avg: float  # moyenne sur PACE_WINDOW_DAYS
    tasks_per_day_avg: float
    minutes_today: int
    tasks_today: int
    daily_objective_minutes: int | None
    objective_display_unit: str  # 'min' ou 'tasks'


class PaceResponse(BaseModel):
    window_days: int
    users: list[UserPace]


class MaintenanceRateResponse(BaseModel):
    minutes_per_day: float
    task_count_contributing: int


class ObjectiveResponse(BaseModel):
    daily_objective_minutes: int | None
    display_unit: str  # 'min' ou 'tasks'


class ObjectiveUpdate(BaseModel):
    daily_objective_minutes: int | None = Field(default=None, ge=0, le=480)
    display_unit: str | None = Field(default=None, pattern=r"^(min|tasks)$")


# --- Endpoints ---


@router.get("/retard", response_model=RetardResponse)
async def get_retard(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Retourne les taches en retard (etat RED) du foyer actif.

    Utilise le modele d'urgence canonique jour-granulaire
    (compute_urgency_state == RED) avec home freeze v2, exactement comme la
    liste Android (TaskUrgencyCalculator) et les suggestions gamification. Une
    tache est "en retard" ssi `effective_today > due_date` (jour entier), ou
    effective_today recule des jours d'absence confirmes pour les taches
    requires_home quand le freeze foyer est actif.

    Les ponctuelles (sans recurrence_rule) et les 'uncertain' n'ont pas
    d'echeance et sont exclues. Une tache jamais completee a pour echeance sa
    created_at.date() (via compute_due_date) : elle n'est en retard que le
    lendemain de sa creation, pas immediatement.

    Somme des minutes (task.weight * 5) et liste des task_ids.
    """
    foyer_uuid = current_foyer_uuid(current)
    tasks_with_last = await _fetch_foyer_tasks_with_last_completion(session, foyer_uuid)

    today = datetime.now(timezone.utc).date()
    last_completion_map = {task.id: last for task, last in tasks_with_last}
    freeze_enabled, freezes_by_task, absent_days_map = await load_freeze_context(
        session,
        foyer_uuid,
        (task for task, _ in tasks_with_last),
        last_completion_map,
        today,
    )

    overdue: list[RetardTask] = []
    total_minutes = 0
    for task, last_completed in tasks_with_last:
        if task.recurrence_rule is None:
            continue  # ponctuelles exclues
        if is_uncertain(task.recurrence_rule):
            continue  # uncertain : jamais en retard (Q2, Q10)
        due_date = compute_due_date(task.recurrence_rule, last_completed, task.created_at)
        nominal = compute_nominal_interval_days(task.recurrence_rule)
        eff_today = compute_effective_today(
            today,
            task_requires_home=freezes_by_task.get(task.id, False),
            home_freeze_enabled=freeze_enabled,
            absent_days=absent_days_map.get(task.id, 0),
        )
        if compute_urgency_state(due_date, eff_today, nominal, grace_days_of(task.recurrence_rule)) != UrgencyState.RED:
            continue
        minutes = _task_minutes(task)
        total_minutes += minutes
        overdue.append(
            RetardTask(
                task_id=task.id,
                title=task.title,
                minutes=minutes,
                overdue_since=last_completed,
            )
        )

    return RetardResponse(
        total_minutes=total_minutes,
        task_count=len(overdue),
        tasks=overdue,
    )


@router.get("/pace", response_model=PaceResponse)
async def get_pace(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Retourne le rythme moyen 14 derniers jours de chaque user du foyer.

    Calcule pour chaque user :
    - Minutes/jour moyennes (somme des task.weight * 5 sur les completions
      des 14 derniers jours, divisee par 14)
    - Taches/jour moyennes (count / 14)
    - Minutes/taches realisees aujourd'hui (compteur du jour pour le
      progress bar des cards)
    - Objectif perso + unite preferee (depuis user_foyers)
    """
    foyer_uuid = current_foyer_uuid(current)
    now = datetime.now(timezone.utc)
    cutoff = now - timedelta(days=PACE_WINDOW_DAYS)
    today_start = datetime(now.year, now.month, now.day, tzinfo=timezone.utc)

    # Users du foyer
    users_result = await session.execute(
        select(User, UserFoyer.daily_objective_minutes, UserFoyer.objective_display_unit)
        .join(UserFoyer, UserFoyer.user_id == User.id)
        .where(UserFoyer.foyer_id == foyer_uuid)
        .order_by(User.display_name)
    )
    users_rows = users_result.all()

    # Completions 14j scoped foyer, avec task.weight pour calculer les minutes.
    completions_result = await session.execute(
        select(
            TaskCompletion.completed_by,
            TaskCompletion.completed_at,
            Task.weight,
        )
        .join(Task, Task.id == TaskCompletion.task_id)
        .where(
            TaskCompletion.completed_at >= cutoff,
            TaskCompletion.deleted_at.is_(None),
            Task.foyer_id == foyer_uuid,
        )
    )
    rows = completions_result.all()

    # Agregation : par user, (minutes_total_14j, tasks_14j, minutes_today, tasks_today)
    agg: dict[uuid.UUID, dict[str, int]] = {}
    for completed_by, completed_at, weight in rows:
        minutes = max(1, weight or 1) * MINUTES_PER_POINT
        bucket = agg.setdefault(
            completed_by,
            {"min_14": 0, "tasks_14": 0, "min_today": 0, "tasks_today": 0},
        )
        bucket["min_14"] += minutes
        bucket["tasks_14"] += 1
        if completed_at >= today_start:
            bucket["min_today"] += minutes
            bucket["tasks_today"] += 1

    users: list[UserPace] = []
    for user, objective, unit in users_rows:
        bucket = agg.get(user.id, {"min_14": 0, "tasks_14": 0, "min_today": 0, "tasks_today": 0})
        users.append(
            UserPace(
                user_id=user.id,
                display_name=user.display_name,
                minutes_per_day_avg=bucket["min_14"] / PACE_WINDOW_DAYS,
                tasks_per_day_avg=bucket["tasks_14"] / PACE_WINDOW_DAYS,
                minutes_today=bucket["min_today"],
                tasks_today=bucket["tasks_today"],
                daily_objective_minutes=objective,
                objective_display_unit=unit or "min",
            )
        )

    return PaceResponse(window_days=PACE_WINDOW_DAYS, users=users)


@router.get("/maintenance-rate", response_model=MaintenanceRateResponse)
async def get_maintenance_rate(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Retourne la rate de maintenance — min/j combine pour maintenir zero retard.

    Somme sur toutes les taches recurrentes actives du foyer de
    `task.weight * 5 / (interval_hours/24)`. Les ponctuelles sont exclues.
    """
    foyer_uuid = current_foyer_uuid(current)
    tasks_with_last = await _fetch_foyer_tasks_with_last_completion(session, foyer_uuid)
    minutes_per_day = _compute_maintenance_minutes_per_day(tasks_with_last)
    count = sum(1 for t, _ in tasks_with_last if t.recurrence_rule is not None)
    return MaintenanceRateResponse(
        minutes_per_day=minutes_per_day,
        task_count_contributing=count,
    )


@router.get("/objective", response_model=ObjectiveResponse)
async def get_objective(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Retourne l'objectif perso + unite d'affichage de l'user courant."""
    user_id = uuid.UUID(current["user_id"])
    foyer_uuid = current_foyer_uuid(current)

    result = await session.execute(
        select(UserFoyer).where(
            UserFoyer.user_id == user_id,
            UserFoyer.foyer_id == foyer_uuid,
        )
    )
    uf = result.scalar_one_or_none()
    if uf is None:
        raise LocalizedHTTPException(status_code=404, error_code="USER_FOYER_NOT_FOUND")
    return ObjectiveResponse(
        daily_objective_minutes=uf.daily_objective_minutes,
        display_unit=uf.objective_display_unit or "min",
    )


@router.put("/objective", response_model=ObjectiveResponse)
async def update_objective(
    body: ObjectiveUpdate,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Met a jour l'objectif perso de l'user courant (et/ou l'unite)."""
    user_id = uuid.UUID(current["user_id"])
    foyer_uuid = current_foyer_uuid(current)

    result = await session.execute(
        select(UserFoyer).where(
            UserFoyer.user_id == user_id,
            UserFoyer.foyer_id == foyer_uuid,
        )
    )
    uf = result.scalar_one_or_none()
    if uf is None:
        raise LocalizedHTTPException(status_code=404, error_code="USER_FOYER_NOT_FOUND")

    if "daily_objective_minutes" in body.model_fields_set:
        uf.daily_objective_minutes = body.daily_objective_minutes
    if body.display_unit is not None:
        uf.objective_display_unit = body.display_unit

    await session.commit()
    await session.refresh(uf)
    return ObjectiveResponse(
        daily_objective_minutes=uf.daily_objective_minutes,
        display_unit=uf.objective_display_unit or "min",
    )
