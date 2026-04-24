"""Routes gamification — affinites, suggestions, taches en cours."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel, Field
from sqlalchemy import and_, delete, func, or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.models.category import Category
from app.models.completion import TaskCompletion
from app.models.enums import TaskTolerance, UrgencyState
from app.models.fair_share import FairShareConfig
from app.models.foyer import Foyer
from app.models.task import Task
from app.models.task_affinity import TaskAffinity
from app.models.task_assignment import TaskAssignment
from app.models.task_effort import TaskEffort
from app.models.task_frequency_vote import TaskFrequencyVote
from app.models.task_in_progress import TaskInProgress
from app.services.frequency_vote import upsert_vote_and_sync_consensus
from app.services.presence import get_absent_days_map
from app.services.recurrence import (
    COLD_START_INTERVAL_DAYS,
    UNCERTAIN_SCORE_CAP,
    compute_days_until_due,
    compute_due_date,
    compute_effective_today,
    compute_nominal_interval_days,
    compute_urgency_state,
    is_uncertain,
)
from app.utils.changelog import record_change
from app.utils.scoping import current_foyer_uuid

router = APIRouter(prefix="/api", tags=["gamification"])

TIMEOUT_HOURS = 6


async def _ensure_user_assigned(
    session: AsyncSession,
    task_id: UUID,
    user_id: UUID,
) -> None:
    """Refuse 403 si l'user courant n'est pas assigne a la tache.

    Garde defense-en-profondeur pour les endpoints de notation (affinity,
    effort, frequency). Coherent avec /pending-affinity qui ne propose ces
    taches qu'aux users assignes : afficher ou accepter un vote de quelqu'un
    hors-assignement serait trompeur et envoie un mauvais message.
    """
    existing = await session.execute(
        select(TaskAssignment.user_id).where(
            TaskAssignment.task_id == task_id,
            TaskAssignment.user_id == user_id,
        )
    )
    if existing.scalar_one_or_none() is None:
        raise HTTPException(
            status_code=403,
            detail="Vous n'etes pas assigne a cette tache — vote non accepte.",
        )


# --- Schemas ---

class AffinityUpdate(BaseModel):
    score: int = Field(ge=1, le=10)


class EffortUpdate(BaseModel):
    points: int = Field(ge=1, le=12)  # 1pt=5min, 12pt=1h (max slider UI)


class FrequencyUpdate(BaseModel):
    # Cap a 365j : couvre les taches annuelles (controle technique, decorations
    # saisonnieres) sans permettre des valeurs aberrantes. L'ancien cap a 60j
    # creait des 422 silencieux pour tout slider UI qui allait plus loin
    # (ex : currentDays=60 -> max UI = 120), piegeant la pending_operation
    # indefiniment cote Android.
    every_n_days: int = Field(ge=1, le=365)


class AffinityResponse(BaseModel):
    task_id: UUID
    user_id: UUID
    score: int


class SuggestionResponse(BaseModel):
    task_id: UUID
    title: str
    category_id: UUID | None
    weight: int
    state: str  # "GREEN" / "ORANGE" / "RED" — etat d'urgence au sens du modele echeance
    days_until_due: int  # positif = dans le futur, 0 = aujourd'hui, negatif = en retard
    affinity_score: int
    reason: str  # "urgent" ou "affinity"
    # v1.1 bonus/malus : true si cette tache franchira le seuil_bonus a la
    # prochaine completion (en retard suffisant + deja faite >= 1 fois).
    # Alimente la pastille +X% sur la SuggestionCard.
    bonus_eligible: bool = False
    bonus_pct: float = 0.0  # pct de bonus (ex 0.5 pour +50%)


class SuggestionsResponse(BaseModel):
    """Enveloppe de /suggestions — v0.16 : ajoute remaining_by_tier.

    Le client Android sait ainsi combien de candidats restent par tier
    (hors dismiss list envoyee via ?exclude=). Quand remaining_by_tier[tier]
    == 0, le front peut declencher le recycle (clear global des dismiss +
    refetch sans exclude) et afficher un Snackbar tier-aware.
    """
    suggestions: list[SuggestionResponse]
    remaining_by_tier: dict[str, int]


class DismissRequest(BaseModel):
    """Payload du POST /suggestions/dismiss (log analytics fire-and-forget)."""
    task_id: UUID
    tier: str = Field(pattern="^(urgent|affinity|uncertain)$")


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
    await _ensure_user_assigned(session, task_id, user_id)

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
        foyer_id=current["foyer_id"],
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
    current: dict = Depends(get_current_user),
):
    """Retourne les affinites et efforts pour une tache — UNIQUEMENT pour les
    users assignes a la tache (pas tout le foyer).

    Motivation : on ne demande pas l'affinite a un user qui n'est pas concerne
    (cf /api/pending-affinity qui filtre par TaskAssignment). Afficher une ligne
    vide "Affinite de ***REMOVED*** : -" pour une tache attribuee a Quentin seulement
    est trompeur et suggere un oubli.
    """
    from app.models.user import User

    # Charger uniquement les users ASSIGNES a cette tache dans le foyer actif.
    users_result = await session.execute(
        select(User)
        .join(TaskAssignment, TaskAssignment.user_id == User.id)
        .where(TaskAssignment.task_id == task_id)
        .order_by(User.username)
    )
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

    # Trouver toutes les taches assignees actives (scope foyer).
    # Memes filtres anti-spam que /pending-affinity : ponctuelles exclues +
    # seuil 2 completions / 1j minimum avant d'etre proposee a la notation.
    tasks_result = await session.execute(
        select(Task).where(
            Task.id.in_(assigned),
            Task.foyer_id == current_foyer_uuid(current),
            Task.deleted_at.is_(None),
            Task.is_active.is_(True),
            Task.recurrence_rule.isnot(None),
            Task.id.in_(_eligible_for_rating_subq()),
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
    await _ensure_user_assigned(session, task_id, user_id)

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
        foyer_id=current["foyer_id"],
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
    await _ensure_user_assigned(session, task_id, user_id)

    task_result = await session.execute(
        select(Task).where(Task.id == task_id, Task.deleted_at.is_(None))
    )
    task = task_result.scalar_one_or_none()
    if not task:
        raise HTTPException(status_code=404, detail="Tache introuvable")

    old_rule = dict(task.recurrence_rule) if task.recurrence_rule else None
    consensus_before, consensus_after = await upsert_vote_and_sync_consensus(
        session, task, user_id, body.every_n_days
    )

    # Log du changement de la tache (cote sync) — sera dedoublonne en GET /history.
    await record_change(
        session, "tasks", task_id, "UPDATE", current["user_id"],
        foyer_id=current["foyer_id"],
        old_data={"recurrence_rule": old_rule},
        new_data={"recurrence_rule": task.recurrence_rule},
    )
    # Log du vote individuel (alimente l'historique UI).
    await record_change(
        session, "task_frequency_votes", task_id, "VOTE", current["user_id"],
        foyer_id=current["foyer_id"],
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


@router.get("/tasks/{task_id}/frequency-votes")
async def get_frequency_votes(
    task_id: UUID,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Retourne les votes individuels et le consensus pour une tache. Sert
    a alimenter le mini-dialog "Modifier via le vote" et la ligne
    "Consensus : Xj (N votes)" sur la fiche d'edition.

    Scope : foyer (tous les membres peuvent lire). Champ `can_vote=true`
    uniquement si l'user courant est assigne a la tache.
    """
    from app.models.user import User

    user_id = UUID(current["user_id"])
    foyer_uuid = current_foyer_uuid(current)

    task_r = await session.execute(
        select(Task).where(
            Task.id == task_id,
            Task.foyer_id == foyer_uuid,
            Task.deleted_at.is_(None),
        )
    )
    task = task_r.scalar_one_or_none()
    if not task:
        raise HTTPException(status_code=404, detail="Tache introuvable")

    # Votes + display_name des voteurs
    votes_r = await session.execute(
        select(TaskFrequencyVote, User)
        .join(User, User.id == TaskFrequencyVote.user_id)
        .where(TaskFrequencyVote.task_id == task_id)
    )
    rows = votes_r.all()

    my_vote = None
    others = []
    for vote, user in rows:
        if vote.user_id == user_id:
            my_vote = vote.every_n_days
        else:
            others.append({
                "user_id": str(vote.user_id),
                "display_name": user.display_name,
                "every_n_days": vote.every_n_days,
            })

    # can_vote = user assigne a la tache
    assign_r = await session.execute(
        select(TaskAssignment.task_id).where(
            TaskAssignment.task_id == task_id,
            TaskAssignment.user_id == user_id,
        )
    )
    can_vote = assign_r.scalar_one_or_none() is not None

    consensus = None
    if task.recurrence_rule and isinstance(task.recurrence_rule.get("every_n_days"), int):
        consensus = task.recurrence_rule["every_n_days"]

    return {
        "task_id": str(task_id),
        "consensus_every_n_days": consensus,
        "votes_count": len(rows),
        "my_vote": my_vote,
        "can_vote": can_vote,
        "others": others,
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


def _eligible_for_rating_subq():
    """Sous-requete des task_id eligibles a la notation (affinite/effort).

    Une tache est eligible si elle a ete completee >=2 fois ET que la
    derniere completion remonte a >=1j. Empeche le prompt de notation de
    s'afficher des la creation d'une tache (double travail) : on attend que
    l'utilisateur ait pu eprouver la tache pour pouvoir la noter sincerement.
    """
    one_day_ago = datetime.now(timezone.utc) - timedelta(days=1)
    return (
        select(TaskCompletion.task_id)
        .where(TaskCompletion.deleted_at.is_(None))
        .group_by(TaskCompletion.task_id)
        .having(
            and_(
                func.count(TaskCompletion.id) >= 2,
                func.max(TaskCompletion.completed_at) <= one_day_ago,
            )
        )
        .scalar_subquery()
    )


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
    # (scope foyer). Filtres anti-spam : on exclut les ponctuelles et on
    # attend qu'une tache ait ete completee >=2 fois avec la derniere >=1j
    # avant de la proposer a la notation (sinon on prompt des la creation).
    pending_condition = [
        Task.id.in_(assigned),
        Task.foyer_id == current_foyer_uuid(current),
        or_(Task.id.notin_(rated_affinity), Task.id.notin_(rated_effort)),
        Task.deleted_at.is_(None),
        Task.is_active.is_(True),
        Task.recurrence_rule.isnot(None),
        Task.id.in_(_eligible_for_rating_subq()),
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

def _build_suggestion(scored: dict, reason: str) -> SuggestionResponse:
    """Construit un SuggestionResponse a partir d'un dict scored_tasks."""
    return SuggestionResponse(
        task_id=scored["task"].id,
        title=scored["task"].title,
        category_id=scored["task"].category_id,
        weight=scored["task"].weight,
        state=scored["state"].value,
        days_until_due=scored["days_until_due"],
        affinity_score=scored["affinity_score"],
        reason=reason,
        bonus_eligible=scored.get("bonus_eligible", False),
        bonus_pct=scored.get("bonus_pct", 0.0),
    )


def _build_uncertain_suggestion(scored: dict) -> SuggestionResponse:
    """Construit un SuggestionResponse pour le bucket 'Sans cadence' (Q2, Q8, Q9).

    state toujours GREEN, days_until_due = 0 (Android ignore ce champ pour
    uncertain et recalcule son label 'Fait il y a X j' via TaskUrgencyCalculator
    a partir du dernier completed_at local).
    """
    return SuggestionResponse(
        task_id=scored["task"].id,
        title=scored["task"].title,
        category_id=scored["task"].category_id,
        weight=scored["task"].weight,
        state=UrgencyState.GREEN.value,
        days_until_due=0,
        affinity_score=scored["affinity_score"],
        reason="uncertain",
        bonus_eligible=False,
        bonus_pct=0.0,
    )


@router.get("/suggestions", response_model=SuggestionsResponse)
async def get_suggestions(
    limit: int = Query(5, ge=1, le=20),
    exclude: str | None = Query(
        None,
        description="CSV d'UUIDs de taches a exclure du pool (dismiss list client).",
    ),
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Retourne les suggestions de taches — N urgentes + N affinites (default N=5).

    Le client Android utilise un tampon local de plusieurs suggestions pour
    pouvoir afficher instantanement la suivante apres une completion, sans
    attendre que SyncWorker ait pousse la completion au backend (v0.11.1).
    L'ordre est celui de la cascade ci-dessous ; la premiere entree de
    chaque tier reste "la plus urgente" / "celle que tu aimes le plus".

    Cascade "la plus urgente" (remplit jusqu'a `limit` dans l'ordre) :
      1. Rouges STRICT  tries par ratio (days_overdue / nominal_interval) desc
      2. puis Rouges NORMAL (meme tri)
      3. puis Rouges SOUPLE (meme tri)
      4. puis Oranges flat tries par (due_date - today) asc
      5. stop des qu'on a `limit` urgentes collectees

    Cascade "tu aimes bien" (remplit jusqu'a `limit` affinites) :
      1. Rouges (toutes tolerances, excluant les urgentes deja picked) tries par
         affinite normalisee desc
      2. puis Oranges (idem exclusion) tries par affinite normalisee desc
      3. stop des qu'on a `limit` affinites collectees
    """
    user_id = UUID(current["user_id"])

    # Parser exclude (CSV UUIDs). UUIDs invalides -> silently ignores pour
    # que le client ne s'enraye pas sur un id corrompu dans sa session.
    exclude_ids: set[UUID] = set()
    if exclude:
        for raw in exclude.split(","):
            raw = raw.strip()
            if not raw:
                continue
            try:
                exclude_ids.add(UUID(raw))
            except ValueError:
                continue

    # Taches assignees a l'user, actives, non supprimees
    assigned = select(TaskAssignment.task_id).where(
        TaskAssignment.user_id == user_id
    ).scalar_subquery()

    # Exclure les taches en cours par d'autres
    in_progress_ids = select(TaskInProgress.task_id).where(
        TaskInProgress.user_id != user_id
    ).scalar_subquery()

    # On ne filtre PAS sur exclude_ids ici : on a besoin du pool complet
    # pour calculer remaining_by_tier (combien de candidats existent au
    # total par tier, hors dismiss) apres le scoring. L'application du
    # filtre dismiss se fait plus bas sur les buckets finaux.
    result = await session.execute(
        select(Task).where(
            Task.id.in_(assigned),
            Task.id.notin_(in_progress_ids),
            Task.foyer_id == current_foyer_uuid(current),
            Task.deleted_at.is_(None),
            Task.is_active.is_(True),
        )
    )
    tasks = result.scalars().all()
    if not tasks:
        return SuggestionsResponse(
            suggestions=[],
            remaining_by_tier={"urgent": 0, "affinity": 0, "uncertain": 0},
        )

    # Charger derniere completion par tache + count pour bonus_eligible
    task_ids = [t.id for t in tasks]
    comp_result = await session.execute(
        select(TaskCompletion.task_id, func.max(TaskCompletion.completed_at)).where(
            TaskCompletion.task_id.in_(task_ids),
            TaskCompletion.deleted_at.is_(None),
        ).group_by(TaskCompletion.task_id)
    )
    last_completions = {row[0]: row[1] for row in comp_result.all()}

    count_result = await session.execute(
        select(TaskCompletion.task_id, func.count(TaskCompletion.id)).where(
            TaskCompletion.task_id.in_(task_ids),
            TaskCompletion.deleted_at.is_(None),
        ).group_by(TaskCompletion.task_id)
    )
    completion_counts = {row[0]: row[1] for row in count_result.all()}

    # Config bonus/malus du foyer (une seule fetch).
    from app.services.bonus_malus import load_or_create_bonus_config
    foyer_uuid = current_foyer_uuid(current)
    bonus_config = await load_or_create_bonus_config(session, foyer_uuid)
    _seuil_bonus_map = {
        TaskTolerance.STRICT: bonus_config.seuil_bonus_strict,
        TaskTolerance.NORMAL: bonus_config.seuil_bonus_normal,
        TaskTolerance.SOUPLE: bonus_config.seuil_bonus_souple,
    }

    # Date du jour (utilisee pour le freeze map et le scoring urgence).
    today = datetime.now(timezone.utc).date()

    # --- Home freeze v2 : charger foyer + categories requires_home ---
    foyer_result = await session.execute(select(Foyer).where(Foyer.id == foyer_uuid))
    foyer = foyer_result.scalar_one_or_none()
    freeze_enabled = foyer.home_freeze_enabled if foyer else False

    # Map category_id -> requires_home
    cat_ids = {t.category_id for t in tasks if t.category_id}
    requires_home_map: dict[UUID | None, bool] = {}
    if cat_ids and freeze_enabled:
        cat_result = await session.execute(
            select(Category.id, Category.requires_home).where(Category.id.in_(cat_ids))
        )
        requires_home_map = {row[0]: row[1] for row in cat_result.all()}

    # Compter les jours absents en batch pour les taches requires_home
    absent_days_map: dict[UUID, int] = {}
    if freeze_enabled:
        # Construire la map {task_id: since_date} pour les taches requires_home
        since_dates = {}
        for t in tasks:
            if requires_home_map.get(t.category_id, False):
                lc = last_completions.get(t.id)
                since = lc.date() if lc else (t.created_at.date() if t.created_at else today)
                since_dates[t.id] = since
        if since_dates:
            absent_days_map = await get_absent_days_map(session, foyer_uuid, since_dates, today)

    # Charger affinites de l'user
    aff_result = await session.execute(
        select(TaskAffinity.task_id, TaskAffinity.score).where(
            TaskAffinity.task_id.in_(task_ids),
            TaskAffinity.user_id == user_id,
        )
    )
    affinities = {row[0]: row[1] for row in aff_result.all()}

    # Affinites normalisees (pour le classement affinite cross-taches)
    aff_scores = [affinities.get(t.id, 5) for t in tasks]
    mean_aff = sum(aff_scores) / len(aff_scores) if aff_scores else 5.0
    std_aff = (sum((s - mean_aff) ** 2 for s in aff_scores) / len(aff_scores)) ** 0.5 if aff_scores else 1.0
    if std_aff == 0:
        std_aff = 1.0

    # Scoring par tache : due_date, state, ratio retard, affinite.
    # Les taches 'uncertain' sont scorees separement (bucket dedie Q8).
    scored_tasks = []
    uncertain_scored: list[dict] = []
    for task in tasks:
        last_comp = last_completions.get(task.id)

        # Bucket uncertain : score base sur maturite d/i (Q8), pas sur urgence.
        if is_uncertain(task.recurrence_rule):
            interval = task.inferred_interval_days or float(COLD_START_INTERVAL_DAYS)
            if last_comp is None:
                # 0 completion : top de section (Q4 cold start optimiste).
                score = float("inf")
                d = None
            else:
                d = (today - last_comp.date()).days
                score = min(d / interval, UNCERTAIN_SCORE_CAP)
            uncertain_scored.append({
                "task": task,
                "score": score,
                "days_since_last": d,
                "affinity_score": affinities.get(task.id, 5),
            })
            continue

        due_date = compute_due_date(task.recurrence_rule, last_comp, task.created_at)
        nominal = compute_nominal_interval_days(task.recurrence_rule)

        # Home freeze v2 : decaler effective_today pour les taches requires_home
        task_requires_home = requires_home_map.get(task.category_id, False)
        task_absent_days = absent_days_map.get(task.id, 0)
        eff_today = compute_effective_today(
            today,
            task_requires_home=task_requires_home,
            home_freeze_enabled=freeze_enabled,
            absent_days=task_absent_days,
        )

        state = compute_urgency_state(due_date, eff_today, nominal)
        days_until = compute_days_until_due(due_date, eff_today)
        if days_until is None:
            # Ponctuelle deja faite — n'apparait pas dans les suggestions.
            continue
        # Ratio de retard : (jours en retard) / intervalle nominal. Normalise
        # l'urgence relative a l'intervalle (une quotidienne en retard de 3j
        # bat une mensuelle en retard de 5j).
        if state == UrgencyState.RED and nominal > 0:
            ratio = (-days_until) / float(nominal)
        else:
            ratio = 0.0

        raw_aff = affinities.get(task.id, 5)
        norm_aff = (raw_aff - mean_aff) / std_aff

        # Bonus eligible : en retard + au-dela du seuil + au moins une completion
        # passee (bypass first-completion).
        bonus_eligible = False
        if state == UrgencyState.RED and nominal > 0:
            seuil = _seuil_bonus_map.get(task.tolerance, bonus_config.seuil_bonus_normal)
            pct_retard = (-days_until) / float(nominal)
            if pct_retard >= seuil and completion_counts.get(task.id, 0) >= 1:
                bonus_eligible = True

        scored_tasks.append({
            "task": task,
            "due_date": due_date,
            "state": state,
            "days_until_due": days_until,
            "overdue_ratio": ratio,
            "affinity_score": raw_aff,
            "norm_affinity": norm_aff,
            "bonus_eligible": bonus_eligible,
            "bonus_pct": bonus_config.bonus_pct if bonus_eligible else 0.0,
        })

    # Buckets par tier pour la cascade "la plus urgente".
    # On filtre dismiss (exclude_ids) ici sur chaque bucket — ca propage
    # automatiquement aux cascades urgent et affinity. Le comptage
    # remaining_by_tier se base ensuite sur ces buckets filtres.
    def _not_excluded(t: dict) -> bool:
        return t["task"].id not in exclude_ids

    red_strict = [t for t in scored_tasks if t["state"] == UrgencyState.RED and t["task"].tolerance == TaskTolerance.STRICT and _not_excluded(t)]
    red_normal = [t for t in scored_tasks if t["state"] == UrgencyState.RED and t["task"].tolerance == TaskTolerance.NORMAL and _not_excluded(t)]
    red_souple = [t for t in scored_tasks if t["state"] == UrgencyState.RED and t["task"].tolerance == TaskTolerance.SOUPLE and _not_excluded(t)]
    oranges = [t for t in scored_tasks if t["state"] == UrgencyState.ORANGE and _not_excluded(t)]
    uncertain_scored = [s for s in uncertain_scored if s["task"].id not in exclude_ids]

    # Cascade "urgent" : remplit jusqu'a `limit` en parcourant les tiers dans
    # l'ordre rouge_strict -> rouge_normal -> rouge_souple -> oranges.
    # Tri intra-tier : rouges par ratio retard desc, oranges par days_until_due asc.
    urgent_picks: list[dict] = []

    def _fill(tier: list[dict], sort_key) -> None:
        if len(urgent_picks) >= limit:
            return
        for t in sorted(tier, key=sort_key):
            if len(urgent_picks) >= limit:
                break
            urgent_picks.append(t)

    _fill(red_strict, lambda t: (-t["overdue_ratio"], t["days_until_due"]))
    _fill(red_normal, lambda t: (-t["overdue_ratio"], t["days_until_due"]))
    _fill(red_souple, lambda t: (-t["overdue_ratio"], t["days_until_due"]))
    _fill(oranges, lambda t: t["days_until_due"])

    # Cascade "affinite" : exclut les urgents deja picked, tri par affinite
    # normalisee desc sur rouges d'abord, puis oranges.
    urgent_ids = {p["task"].id for p in urgent_picks}
    reds_all = red_strict + red_normal + red_souple
    red_pool = [t for t in reds_all if t["task"].id not in urgent_ids]
    orange_pool = [t for t in oranges if t["task"].id not in urgent_ids]

    affinity_picks: list[dict] = []
    for t in sorted(red_pool, key=lambda t: -t["norm_affinity"]):
        if len(affinity_picks) >= limit:
            break
        affinity_picks.append(t)
    if len(affinity_picks) < limit:
        for t in sorted(orange_pool, key=lambda t: -t["norm_affinity"]):
            if len(affinity_picks) >= limit:
                break
            affinity_picks.append(t)

    # Bucket "Sans cadence" (Q3 3e section, Q8 top-5 par score desc puis
    # days_since_last desc, created_at asc en tie-break ultime).
    # Cold start (0 completion) : score = inf -> primary=-inf, passe en premier.
    def _uncertain_sort_key(s: dict):
        primary = -s["score"]  # score desc ; -inf pour 0-completion, bat tout
        d = s["days_since_last"]
        secondary = -(d if d is not None else 0)  # older completion first
        tertiary = s["task"].created_at or datetime.min.replace(tzinfo=timezone.utc)
        return (primary, secondary, tertiary)

    uncertain_picks: list[dict] = sorted(uncertain_scored, key=_uncertain_sort_key)[:limit]

    suggestions: list[SuggestionResponse] = []
    for pick in urgent_picks:
        suggestions.append(_build_suggestion(pick, "urgent"))
    for pick in affinity_picks:
        suggestions.append(_build_suggestion(pick, "affinity"))
    for pick in uncertain_picks:
        suggestions.append(_build_uncertain_suggestion(pick))

    # remaining_by_tier = nombre total de candidats disponibles par tier
    # APRES filtrage des dismiss. Sert au front a savoir quand un tier est
    # epuise et qu'il faut recycler (clear dismiss + refetch sans exclude).
    # Le tier urgent compte les rouges+oranges (toute la cascade urgent),
    # affinity compte aussi rouges+oranges (memes taches vues sous un
    # autre tri), uncertain compte le bucket dedie.
    urgent_pool_size = len(red_strict) + len(red_normal) + len(red_souple) + len(oranges)
    remaining = {
        "urgent": urgent_pool_size,
        "affinity": urgent_pool_size,
        "uncertain": len(uncertain_scored),
    }
    return SuggestionsResponse(suggestions=suggestions, remaining_by_tier=remaining)


@router.post("/suggestions/dismiss", status_code=204)
async def log_dismiss(
    payload: DismissRequest,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Log analytics fire-and-forget : enregistre un ✕ sur une carte Home.

    Aucun impact sur l'algo de selection des suggestions. Sert uniquement
    aux stats a posteriori (quelle tache est evitee, par qui, combien de
    fois). Pas de validation metier sur l'existence de la task — si la
    FK casse (tache supprimee entretemps), l'insert echoue et on renvoie
    quand meme 204 cote client (best-effort, on ne veut pas propager
    d'erreur pour un log).
    """
    from app.models.suggestion_dismissal import SuggestionDismissal

    try:
        session.add(SuggestionDismissal(
            user_id=UUID(current["user_id"]),
            foyer_id=current_foyer_uuid(current),
            task_id=payload.task_id,
            tier=payload.tier,
        ))
        await session.commit()
    except Exception:
        # Best-effort : on drop silencieusement (race condition avec un
        # delete concurrent de la tache, par exemple).
        await session.rollback()
    return None


# --- Fair-share status ---

@router.get("/fair-share/status", response_model=list[FairShareStatus])
async def fair_share_status(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Retourne le statut fair-share : ratio cible vs reel sur les 30 derniers jours.
    Scope foyer : uniquement les users du foyer actif, sur les completions
    du foyer actif, avec la config fair_share scopee par foyer.
    """
    from app.models.user import User
    from app.models.user_foyer import UserFoyer
    from app.models.user_settings import UserSettings

    foyer_uuid = current_foyer_uuid(current)
    cutoff = datetime.now(timezone.utc) - timedelta(days=30)

    # Compte total et points par user sur 30 jours (scope foyer via JOIN tasks).
    completions_result = await session.execute(
        select(
            TaskCompletion.completed_by,
            func.sum(TaskCompletion.points_earned),
            func.count(TaskCompletion.id),
        )
        .join(Task, Task.id == TaskCompletion.task_id)
        .where(
            TaskCompletion.completed_at >= cutoff,
            TaskCompletion.deleted_at.is_(None),
            Task.foyer_id == foyer_uuid,
        ).group_by(TaskCompletion.completed_by)
    )
    user_points: dict[UUID, int] = {}
    user_counts: dict[UUID, int] = {}
    for row in completions_result.all():
        user_points[row[0]] = row[1] or 0
        user_counts[row[0]] = row[2] or 0
    total_points = sum(user_points.values()) or 1
    total_completions = sum(user_counts.values())

    # Seuil minimum de complétions cumulees (idem).
    min_completions = 10
    has_enough_data = total_completions >= min_completions

    # Configs fair-share scope foyer.
    fs_result = await session.execute(
        select(FairShareConfig).where(FairShareConfig.foyer_id == foyer_uuid)
    )
    fs_configs = {row.user_id: row.target_ratio for row in fs_result.scalars().all()}

    # Tolerances perso (reste per-user global, pas per-foyer — c'est un
    # parametre d'affichage personnel indep du foyer).
    tol_result = await session.execute(
        select(UserSettings.user_id, UserSettings.fair_share_tolerance_percent)
    )
    tolerances = {row[0]: row[1] for row in tol_result.all()}

    # Users du foyer actif uniquement.
    users_result = await session.execute(
        select(User)
        .join(UserFoyer, UserFoyer.user_id == User.id)
        .where(UserFoyer.foyer_id == foyer_uuid)
    )
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
