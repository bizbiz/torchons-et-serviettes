"""Routes gamification — affinites, suggestions, taches en cours."""

from __future__ import annotations

import math
from datetime import datetime, timedelta, timezone
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.models.completion import TaskCompletion
from app.models.fair_share import FairShareConfig
from app.models.task import Task
from app.models.task_affinity import TaskAffinity
from app.models.task_assignment import TaskAssignment
from app.models.task_effort import TaskEffort
from app.models.task_frequency_vote import TaskFrequencyVote
from app.models.task_in_progress import TaskInProgress
from app.services.recurrence import compute_freshness
from app.utils.changelog import record_change

router = APIRouter(prefix="/api", tags=["gamification"])

TIMEOUT_HOURS = 6


# --- Schemas ---

class AffinityUpdate(BaseModel):
    score: int = Field(ge=1, le=10)


class EffortUpdate(BaseModel):
    points: int = Field(ge=1, le=12)  # 1pt=5min, 12pt=1h (max slider UI)


class FrequencyUpdate(BaseModel):
    every_n_days: int = Field(ge=1, le=60)


class AffinityResponse(BaseModel):
    task_id: UUID
    user_id: UUID
    score: int


class SuggestionResponse(BaseModel):
    task_id: UUID
    title: str
    category_id: UUID | None
    weight: int
    freshness: float
    affinity_score: int
    reason: str  # "urgent" ou "affinity"


class InProgressResponse(BaseModel):
    task_id: UUID
    title: str
    user_id: UUID
    started_at: datetime


class FairShareStatus(BaseModel):
    user_id: UUID
    display_name: str
    target_ratio: float
    actual_ratio: float
    above_target: bool


# --- Affinites ---

@router.get("/my-affinities")
async def my_affinities(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Retourne toutes les affinites du user courant (pour filtres)."""
    user_id = UUID(current["user_id"])
    result = await session.execute(
        select(TaskAffinity.task_id, TaskAffinity.score).where(TaskAffinity.user_id == user_id)
    )
    return [{"task_id": str(row[0]), "score": row[1]} for row in result.all()]


@router.get("/tasks/{task_id}/affinity", response_model=AffinityResponse)
async def get_affinity(
    task_id: UUID,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Recupere l'affinite de l'utilisateur courant pour une tache."""
    user_id = UUID(current["user_id"])
    result = await session.execute(
        select(TaskAffinity).where(
            TaskAffinity.task_id == task_id,
            TaskAffinity.user_id == user_id,
        )
    )
    aff = result.scalar_one_or_none()
    if not aff:
        return {"task_id": task_id, "user_id": user_id, "score": 5}
    return {"task_id": aff.task_id, "user_id": aff.user_id, "score": aff.score}


@router.put("/tasks/{task_id}/affinity", response_model=AffinityResponse)
async def set_affinity(
    task_id: UUID,
    body: AffinityUpdate,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Definit l'affinite de l'utilisateur courant pour une tache.
    Enregistre le vote dans change_log avec consensus_before/after pour
    alimenter l'historique de modification (table_name='task_affinities',
    row_id=task_id car PK composite).
    """
    user_id = UUID(current["user_id"])

    # Consensus avant : moyenne arrondie des scores existants.
    cb_result = await session.execute(
        select(func.avg(TaskAffinity.score)).where(TaskAffinity.task_id == task_id)
    )
    cb_raw = cb_result.scalar()
    consensus_before = round(cb_raw) if cb_raw is not None else None

    result = await session.execute(
        select(TaskAffinity).where(
            TaskAffinity.task_id == task_id,
            TaskAffinity.user_id == user_id,
        )
    )
    aff = result.scalar_one_or_none()
    if aff:
        aff.score = body.score
        # Touch updated_at meme si valeur identique (marque comme "revu")
        from sqlalchemy import func as sa_func
        aff.updated_at = sa_func.now()
    else:
        aff = TaskAffinity(task_id=task_id, user_id=user_id, score=body.score)
        session.add(aff)
    await session.flush()

    # Consensus apres : moyenne arrondie apres ce vote.
    ca_result = await session.execute(
        select(func.avg(TaskAffinity.score)).where(TaskAffinity.task_id == task_id)
    )
    ca_raw = ca_result.scalar()
    consensus_after = round(ca_raw) if ca_raw is not None else None

    await record_change(
        session, "task_affinities", task_id, "VOTE", current["user_id"],
        new_data={
            "score": body.score,
            "consensus_before": consensus_before,
            "consensus_after": consensus_after,
        },
    )
    await session.commit()
    return {"task_id": task_id, "user_id": user_id, "score": body.score}


@router.get("/tasks/{task_id}/ratings")
async def get_task_ratings(
    task_id: UUID,
    session: AsyncSession = Depends(get_session),
    _user: dict = Depends(get_current_user),
):
    """Retourne les affinites et efforts de tous les users pour une tache."""
    from app.models.user import User

    # Charger tous les users
    users_result = await session.execute(select(User).order_by(User.username))
    users = users_result.scalars().all()

    # Charger affinites
    aff_result = await session.execute(
        select(TaskAffinity).where(TaskAffinity.task_id == task_id)
    )
    affinities = {a.user_id: a.score for a in aff_result.scalars().all()}

    # Charger efforts
    eff_result = await session.execute(
        select(TaskEffort).where(TaskEffort.task_id == task_id)
    )
    efforts = {e.user_id: e.points for e in eff_result.scalars().all()}

    # Moyenne effort
    effort_values = list(efforts.values())
    avg_effort = sum(effort_values) / len(effort_values) if effort_values else None
    avg_time_min = round(avg_effort * 5) if avg_effort else None

    ratings = []
    for user in users:
        eff_pts = efforts.get(user.id)
        ratings.append({
            "user_id": str(user.id),
            "display_name": user.display_name,
            "affinity": affinities.get(user.id),
            "effort_points": eff_pts,
            "effort_time_min": eff_pts * 5 if eff_pts else None,
        })

    return {
        "ratings": ratings,
        "avg_effort_points": round(avg_effort) if avg_effort else None,
        "avg_effort_time_min": avg_time_min,
        "score_points": round(avg_effort) if avg_effort else None,
    }


@router.get("/oldest-rating", response_model=list[dict])
async def oldest_rating(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Retourne la tache avec la notation la plus ancienne (pour le mode manuel)."""
    user_id = UUID(current["user_id"])

    assigned = select(TaskAssignment.task_id).where(
        TaskAssignment.user_id == user_id
    ).scalar_subquery()

    # Trouver toutes les taches assignees actives
    tasks_result = await session.execute(
        select(Task).where(
            Task.id.in_(assigned),
            Task.deleted_at.is_(None),
            Task.is_active.is_(True),
        )
    )
    tasks = tasks_result.scalars().all()
    if not tasks:
        return []

    # Pour chaque tache, trouver la date la plus ancienne entre affinite et effort
    oldest_task = None
    oldest_date = None
    for task in tasks:
        aff_r = await session.execute(
            select(TaskAffinity.updated_at).where(
                TaskAffinity.task_id == task.id, TaskAffinity.user_id == user_id
            )
        )
        aff_date = aff_r.scalar()

        eff_r = await session.execute(
            select(TaskEffort.updated_at).where(
                TaskEffort.task_id == task.id, TaskEffort.user_id == user_id
            )
        )
        eff_date = eff_r.scalar()

        # La date la plus ancienne des deux (ou None si jamais note)
        dates = [d for d in [aff_date, eff_date] if d is not None]
        task_oldest = min(dates) if dates else None

        if oldest_task is None or task_oldest is None or (oldest_date is not None and task_oldest < oldest_date):
            oldest_task = task
            oldest_date = task_oldest

    if oldest_task is None:
        return []

    return [await _build_pending_payload(session, oldest_task, user_id)]


@router.put("/tasks/{task_id}/effort")
async def set_effort(
    task_id: UUID,
    body: EffortUpdate,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Definit l'effort estime par l'utilisateur courant pour une tache (1pt=5min).
    Enregistre le vote dans change_log avec consensus_before/after (points)
    pour alimenter l'historique de modification.
    """
    user_id = UUID(current["user_id"])

    # Consensus avant : task.weight actuel (deja = round(avg)).
    task_result = await session.execute(select(Task).where(Task.id == task_id))
    task = task_result.scalar_one_or_none()
    consensus_before = task.weight if task else None

    result = await session.execute(
        select(TaskEffort).where(TaskEffort.task_id == task_id, TaskEffort.user_id == user_id)
    )
    eff = result.scalar_one_or_none()
    if eff:
        eff.points = body.points
        from sqlalchemy import func as sa_func
        eff.updated_at = sa_func.now()
    else:
        eff = TaskEffort(task_id=task_id, user_id=user_id, points=body.points)
        session.add(eff)
    await session.flush()

    # Recalculer le weight moyen de la tache = consensus apres.
    avg_result = await session.execute(
        select(func.avg(TaskEffort.points)).where(TaskEffort.task_id == task_id)
    )
    avg = avg_result.scalar()
    consensus_after = round(avg) if avg is not None else None
    if consensus_after is not None and task:
        task.weight = consensus_after

    await record_change(
        session, "task_efforts", task_id, "VOTE", current["user_id"],
        new_data={
            "points": body.points,
            "consensus_before": consensus_before,
            "consensus_after": consensus_after,
        },
    )
    await session.commit()

    return {"task_id": str(task_id), "user_id": str(user_id), "points": body.points}


@router.put("/tasks/{task_id}/frequency")
async def set_frequency(
    task_id: UUID,
    body: FrequencyUpdate,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Definit le vote de frequence (every_n_days) de l'utilisateur courant.
    Recalcule le consensus (moyenne arrondie) et l'ecrit dans
    tasks.recurrence_rule. Enregistre deux entrees change_log :
    - task_frequency_votes/VOTE (value + consensus_before/after)
    - tasks/UPDATE (pour que le client sync recupere la nouvelle recurrence)
    Le GET /tasks/{id}/history dedoublonne ces deux entrees.
    """
    user_id = UUID(current["user_id"])

    task_result = await session.execute(
        select(Task).where(Task.id == task_id, Task.deleted_at.is_(None))
    )
    task = task_result.scalar_one_or_none()
    if not task:
        raise HTTPException(status_code=404, detail="Tache introuvable")

    old_rule = dict(task.recurrence_rule) if task.recurrence_rule else None
    consensus_before = None
    if old_rule and isinstance(old_rule.get("every_n_days"), int):
        consensus_before = old_rule["every_n_days"]

    # Upsert du vote
    result = await session.execute(
        select(TaskFrequencyVote).where(
            TaskFrequencyVote.task_id == task_id,
            TaskFrequencyVote.user_id == user_id,
        )
    )
    vote = result.scalar_one_or_none()
    if vote:
        vote.every_n_days = body.every_n_days
        from sqlalchemy import func as sa_func
        vote.updated_at = sa_func.now()
    else:
        vote = TaskFrequencyVote(
            task_id=task_id, user_id=user_id, every_n_days=body.every_n_days
        )
        session.add(vote)
    await session.flush()

    # Consensus apres : moyenne arrondie des votes de frequence.
    avg_result = await session.execute(
        select(func.avg(TaskFrequencyVote.every_n_days)).where(
            TaskFrequencyVote.task_id == task_id
        )
    )
    avg = avg_result.scalar()
    consensus_after = round(avg) if avg is not None else body.every_n_days

    # Mettre a jour la recurrence de la tache.
    task.recurrence_rule = {"type": "interval", "every_n_days": consensus_after}
    task.version += 1

    # Log du changement de la tache (cote sync) — sera dedoublonne en GET /history.
    await record_change(
        session, "tasks", task_id, "UPDATE", current["user_id"],
        old_data={"recurrence_rule": old_rule},
        new_data={"recurrence_rule": task.recurrence_rule},
    )
    # Log du vote individuel (alimente l'historique UI).
    await record_change(
        session, "task_frequency_votes", task_id, "VOTE", current["user_id"],
        new_data={
            "every_n_days": body.every_n_days,
            "consensus_before": consensus_before,
            "consensus_after": consensus_after,
        },
    )
    await session.commit()

    return {
        "task_id": str(task_id),
        "user_id": str(user_id),
        "every_n_days": body.every_n_days,
        "consensus": consensus_after,
    }


@router.get("/tasks/{task_id}/rating-context")
async def task_rating_context(
    task_id: UUID,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Retourne le payload de notation pour une tache specifique et l'user courant.
    Meme shape que /pending-affinity et /oldest-rating — alimente le modal de
    notation unifie depuis la fiche detail (bouton Modifier).
    """
    user_id = UUID(current["user_id"])
    result = await session.execute(
        select(Task).where(Task.id == task_id, Task.deleted_at.is_(None))
    )
    task = result.scalar_one_or_none()
    if not task:
        raise HTTPException(status_code=404, detail="Tache introuvable")
    return await _build_pending_payload(session, task, user_id)


@router.get("/pending-affinity", response_model=list[dict])
async def pending_affinity(
    expiry_months: int = 6,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Taches assignees a l'user qui n'ont pas d'affinite/effort ou dont c'est expire."""
    user_id = UUID(current["user_id"])
    expiry_cutoff = datetime.now(timezone.utc) - timedelta(days=expiry_months * 30)

    # Taches assignees a l'user, actives
    assigned = select(TaskAssignment.task_id).where(
        TaskAssignment.user_id == user_id
    ).scalar_subquery()

    # Taches avec affinite recente
    rated_affinity = select(TaskAffinity.task_id).where(
        TaskAffinity.user_id == user_id,
        TaskAffinity.updated_at >= expiry_cutoff,
    ).scalar_subquery()

    # Taches avec effort recent
    rated_effort = select(TaskEffort.task_id).where(
        TaskEffort.user_id == user_id,
        TaskEffort.updated_at >= expiry_cutoff,
    ).scalar_subquery()

    # Condition commune : taches sans affinite recente OU sans effort recent
    from sqlalchemy import or_
    pending_condition = [
        Task.id.in_(assigned),
        or_(Task.id.notin_(rated_affinity), Task.id.notin_(rated_effort)),
        Task.deleted_at.is_(None),
        Task.is_active.is_(True),
    ]

    # Compter le total AVANT le limit pour le compteur "X/Y"
    total_result = await session.execute(
        select(func.count(Task.id)).where(*pending_condition)
    )
    pending_total = total_result.scalar() or 0

    if pending_total == 0:
        return []

    result = await session.execute(
        select(Task).where(*pending_condition).order_by(func.random()).limit(1)
    )
    task = result.scalar_one_or_none()
    if not task:
        return []

    payload = await _build_pending_payload(session, task, user_id)
    payload["pending_total"] = pending_total
    return [payload]


async def _build_pending_payload(session: AsyncSession, task: Task, user_id: UUID) -> dict:
    """Construit le payload pour la popup d'affinite/effort avec valeurs pre-remplies.
    Logique 3 cas :
    - Cas 1 (rien) : valeur de base de la tache (weight pour effort, 5 pour affinite)
    - Cas 2 (autres ont note) : moyenne des autres users
    - Cas 3 (moi j'ai note) : ma valeur

    Retourne aussi last_rated_at = max(affinity.updated_at, effort.updated_at)
    filtre sur le user courant — pour afficher "Derniere notation il y a Xj".
    """
    # === Affinité ===
    my_aff_r = await session.execute(
        select(TaskAffinity.score, TaskAffinity.updated_at).where(
            TaskAffinity.task_id == task.id, TaskAffinity.user_id == user_id,
        )
    )
    my_aff_row = my_aff_r.first()
    my_aff = my_aff_row[0] if my_aff_row else None
    my_aff_date = my_aff_row[1] if my_aff_row else None

    if my_aff is not None:
        affinity_value = my_aff
    else:
        # Moyenne des autres users
        others_aff_r = await session.execute(
            select(func.avg(TaskAffinity.score)).where(
                TaskAffinity.task_id == task.id, TaskAffinity.user_id != user_id,
            )
        )
        others_avg = others_aff_r.scalar()
        affinity_value = round(others_avg) if others_avg else 5

    # === Effort ===
    my_eff_r = await session.execute(
        select(TaskEffort.points, TaskEffort.updated_at).where(
            TaskEffort.task_id == task.id, TaskEffort.user_id == user_id,
        )
    )
    my_eff_row = my_eff_r.first()
    my_eff = my_eff_row[0] if my_eff_row else None
    my_eff_date = my_eff_row[1] if my_eff_row else None

    if my_eff is not None:
        effort_value = my_eff
    else:
        others_eff_r = await session.execute(
            select(func.avg(TaskEffort.points)).where(
                TaskEffort.task_id == task.id, TaskEffort.user_id != user_id,
            )
        )
        others_avg = others_eff_r.scalar()
        effort_value = round(others_avg) if others_avg else (task.weight or 3)

    # === Derniere notation (per-user) : max des 2 updated_at ===
    dates = [d for d in (my_aff_date, my_eff_date) if d is not None]
    last_rated_at = max(dates).isoformat() if dates else None

    return {
        "task_id": str(task.id),
        "title": task.title,
        "current_affinity": affinity_value,
        "current_effort_points": effort_value,
        "recurrence_rule": task.recurrence_rule,
        "last_rated_at": last_rated_at,
    }


# --- Taches en cours ---

@router.post("/tasks/{task_id}/start", response_model=InProgressResponse)
async def start_task(
    task_id: UUID,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Marque une tache comme en cours par l'utilisateur."""
    user_id = UUID(current["user_id"])

    # Verifier que la tache existe
    result = await session.execute(
        select(Task).where(Task.id == task_id, Task.deleted_at.is_(None))
    )
    task = result.scalar_one_or_none()
    if not task:
        raise HTTPException(status_code=404, detail="Tache introuvable")

    # Supprimer l'ancien en cours si existant
    await session.execute(delete(TaskInProgress).where(TaskInProgress.task_id == task_id))

    tip = TaskInProgress(task_id=task_id, user_id=user_id)
    session.add(tip)
    await session.commit()
    await session.refresh(tip)
    return {"task_id": task_id, "title": task.title, "user_id": user_id, "started_at": tip.started_at}


@router.delete("/tasks/{task_id}/start")
async def cancel_task_in_progress(
    task_id: UUID,
    session: AsyncSession = Depends(get_session),
    _user: dict = Depends(get_current_user),
):
    """Annule la tache en cours."""
    await session.execute(delete(TaskInProgress).where(TaskInProgress.task_id == task_id))
    await session.commit()
    return {"ok": True}


@router.get("/in-progress", response_model=list[InProgressResponse])
async def list_in_progress(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Liste les taches en cours de l'utilisateur (avec nettoyage timeout 6h)."""
    user_id = UUID(current["user_id"])
    cutoff = datetime.now(timezone.utc) - timedelta(hours=TIMEOUT_HOURS)

    # Supprimer les expirees
    await session.execute(
        delete(TaskInProgress).where(TaskInProgress.started_at < cutoff)
    )
    await session.commit()

    result = await session.execute(
        select(TaskInProgress, Task.title).join(
            Task, Task.id == TaskInProgress.task_id
        ).where(TaskInProgress.user_id == user_id)
    )
    return [
        {"task_id": row[0].task_id, "title": row[1], "user_id": row[0].user_id, "started_at": row[0].started_at}
        for row in result.all()
    ]


# --- Suggestions ---

@router.get("/suggestions", response_model=list[SuggestionResponse])
async def get_suggestions(
    freshness_threshold: float = 80.0,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Retourne les suggestions de taches : la plus urgente et celle avec la meilleure affinite."""
    user_id = UUID(current["user_id"])

    # Taches assignees a l'user, actives, non supprimees
    assigned = select(TaskAssignment.task_id).where(
        TaskAssignment.user_id == user_id
    ).scalar_subquery()

    # Exclure les taches en cours par d'autres
    in_progress_ids = select(TaskInProgress.task_id).where(
        TaskInProgress.user_id != user_id
    ).scalar_subquery()

    result = await session.execute(
        select(Task).where(
            Task.id.in_(assigned),
            Task.id.notin_(in_progress_ids),
            Task.deleted_at.is_(None),
            Task.is_active.is_(True),
            Task.recurrence_rule.isnot(None),  # Exclure les ponctuelles
        )
    )
    tasks = result.scalars().all()
    if not tasks:
        return []

    # Charger completions recentes
    task_ids = [t.id for t in tasks]
    comp_result = await session.execute(
        select(TaskCompletion.task_id, func.max(TaskCompletion.completed_at)).where(
            TaskCompletion.task_id.in_(task_ids),
            TaskCompletion.deleted_at.is_(None),
        ).group_by(TaskCompletion.task_id)
    )
    last_completions = {row[0]: row[1] for row in comp_result.all()}

    # Charger affinites de l'user
    aff_result = await session.execute(
        select(TaskAffinity.task_id, TaskAffinity.score).where(
            TaskAffinity.task_id.in_(task_ids),
            TaskAffinity.user_id == user_id,
        )
    )
    affinities = {row[0]: row[1] for row in aff_result.all()}

    # Calculer affinites normalisees
    aff_scores = [affinities.get(t.id, 5) for t in tasks]
    mean_aff = sum(aff_scores) / len(aff_scores) if aff_scores else 5.0
    std_aff = (sum((s - mean_aff) ** 2 for s in aff_scores) / len(aff_scores)) ** 0.5 if aff_scores else 1.0
    if std_aff == 0:
        std_aff = 1.0

    # Calculer freshness et scores pour chaque tache
    scored_tasks = []
    for task in tasks:
        last_comp = last_completions.get(task.id)
        freshness = compute_freshness(
            task.recurrence_rule, last_comp,
            task.created_at, task.tolerance,
        )
        raw_aff = affinities.get(task.id, 5)
        norm_aff = (raw_aff - mean_aff) / std_aff  # Positif = aime, negatif = deteste

        scored_tasks.append({
            "task": task,
            "freshness": freshness,
            "affinity_score": raw_aff,
            "norm_affinity": norm_aff,
            "urgency_score": 100.0 - freshness,  # Plus c'est bas en freshness, plus c'est urgent
        })

    suggestions = []

    # Ne suggerer que les taches a faire (freshness < seuil)
    tasks_to_do = [t for t in scored_tasks if t["freshness"] < freshness_threshold]
    if not tasks_to_do:
        return []

    # 1. Tache la plus urgente (freshness la plus basse)
    most_urgent = min(tasks_to_do, key=lambda x: x["freshness"])
    suggestions.append(SuggestionResponse(
        task_id=most_urgent["task"].id,
        title=most_urgent["task"].title,
        category_id=most_urgent["task"].category_id,
        weight=most_urgent["task"].weight,
        freshness=most_urgent["freshness"],
        affinity_score=most_urgent["affinity_score"],
        reason="urgent",
    ))

    # 2. Tache avec la meilleure affinite parmi celles a faire (differente de la 1ere)
    remaining = [t for t in tasks_to_do if t["task"].id != most_urgent["task"].id]
    if remaining:
        best_affinity = max(remaining, key=lambda x: x["norm_affinity"])
        suggestions.append(SuggestionResponse(
            task_id=best_affinity["task"].id,
            title=best_affinity["task"].title,
            category_id=best_affinity["task"].category_id,
            weight=best_affinity["task"].weight,
            freshness=best_affinity["freshness"],
            affinity_score=best_affinity["affinity_score"],
            reason="affinity",
        ))

    return suggestions


# --- Fair-share status ---

@router.get("/fair-share/status", response_model=list[FairShareStatus])
async def fair_share_status(
    session: AsyncSession = Depends(get_session),
    _user: dict = Depends(get_current_user),
):
    """Retourne le statut fair-share : ratio cible vs reel sur les 30 derniers jours.

    Le booleen `above_target` integre la tolerance perso de chaque user
    (`fair_share_tolerance_percent` dans user_settings) ET un seuil
    minimum de complétions cumulees, pour eviter d'afficher le message
    de felicitation quand les donnees sont trop maigres.
    """
    from app.models.user import User
    from app.models.user_settings import UserSettings

    cutoff = datetime.now(timezone.utc) - timedelta(days=30)

    # Compte total et points par user sur 30 jours.
    completions_result = await session.execute(
        select(
            TaskCompletion.completed_by,
            func.sum(TaskCompletion.points_earned),
            func.count(TaskCompletion.id),
        ).where(
            TaskCompletion.completed_at >= cutoff,
            TaskCompletion.deleted_at.is_(None),
        ).group_by(TaskCompletion.completed_by)
    )
    user_points: dict[UUID, int] = {}
    user_counts: dict[UUID, int] = {}
    for row in completions_result.all():
        user_points[row[0]] = row[1] or 0
        user_counts[row[0]] = row[2] or 0
    total_points = sum(user_points.values()) or 1
    total_completions = sum(user_counts.values())

    # Seuil minimum de complétions cumulees pour qu'on ose afficher le
    # message "tu as fait plus que ta part". En dessous, le ratio est
    # statistiquement bruite, on se tait.
    min_completions = 10
    has_enough_data = total_completions >= min_completions

    # Configs fair-share (target ratio par user).
    fs_result = await session.execute(select(FairShareConfig))
    fs_configs = {row.user_id: row.target_ratio for row in fs_result.scalars().all()}

    # Tolerances perso (chaque user a sa propre sensibilite).
    tol_result = await session.execute(
        select(UserSettings.user_id, UserSettings.fair_share_tolerance_percent)
    )
    tolerances = {row[0]: row[1] for row in tol_result.all()}

    # Users
    users_result = await session.execute(select(User))
    users = users_result.scalars().all()

    statuses = []
    for user in users:
        target = fs_configs.get(user.id, 0.5)
        actual = user_points.get(user.id, 0) / total_points
        tol = tolerances.get(user.id, 30) / 100.0
        threshold = target * (1.0 + tol)
        statuses.append(FairShareStatus(
            user_id=user.id,
            display_name=user.display_name,
            target_ratio=target,
            actual_ratio=actual,
            above_target=has_enough_data and actual > threshold,
        ))

    return statuses


class FairShareConfigUpdate(BaseModel):
    """Ratio cible pour un utilisateur (ex: 0.6 = 60%)."""
    user_id: UUID
    target_ratio: float = Field(ge=0.0, le=1.0)


@router.put("/fair-share/config")
async def update_fair_share(
    body: list[FairShareConfigUpdate],
    session: AsyncSession = Depends(get_session),
    _user: dict = Depends(get_current_user),
):
    """Met a jour les ratios fair-share. Envoyer la config pour tous les users."""
    for item in body:
        result = await session.execute(
            select(FairShareConfig).where(FairShareConfig.user_id == item.user_id)
        )
        config = result.scalar_one_or_none()
        if config:
            config.target_ratio = item.target_ratio
        else:
            session.add(FairShareConfig(user_id=item.user_id, target_ratio=item.target_ratio))
    await session.commit()
    return {"ok": True}
