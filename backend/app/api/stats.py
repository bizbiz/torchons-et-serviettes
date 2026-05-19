"""Routes statistiques — points, leaderboard, streaks, badges, edition completions."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone, date
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel, Field
from sqlalchemy import delete, func, or_, select, case, extract
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.models.badge import Badge
from app.models.completion import TaskCompletion
from app.models.foyer import Foyer
from app.models.streak import Streak
from app.models.task import Task
from app.models.user import User
from app.services.presence import get_absent_dates
from app.services.recurrence import min_completion_gap_seconds
from app.utils.changelog import record_change
from app.utils.scoping import current_foyer_uuid
from app.utils.text import join_with_amp

router = APIRouter(prefix="/api", tags=["stats"])


# --- Schemas ---

class CompletionUpdate(BaseModel):
    completed_by: UUID | None = None
    completed_at: datetime | None = None


class UserStats(BaseModel):
    user_id: UUID
    display_name: str
    points_week: int
    points_month: int
    points_total: int
    tasks_week: int
    tasks_month: int
    tasks_total: int


class LeaderboardEntry(BaseModel):
    user_id: UUID
    display_name: str
    points: int
    normalized_score: float  # points / target_ratio — comparable entre users
    tasks_count: int
    rank: int


class StreakInfo(BaseModel):
    user_id: UUID
    display_name: str
    current_streak: int
    best_streak: int
    # Dates des deux runs (jours actifs + jours absents PONTES) pour le
    # liseré cote client. Source unique : ce calcul serveur (fini la
    # reimplementation cliente fenetree qui cassait en cross-mois).
    current_run: list[date] = []
    best_run: list[date] = []


class BadgeInfo(BaseModel):
    badge_type: str
    label: str
    description: str
    earned_at: datetime | None = None
    earned: bool = False


# --- Catalogue de badges ---

BADGE_CATALOG = {
    "first_task": ("Première tâche", "Compléter sa première tâche"),
    "tasks_10": ("Débutant", "Compléter 10 tâches"),
    "tasks_50": ("Habitué", "Compléter 50 tâches"),
    "tasks_100": ("Expert", "Compléter 100 tâches"),
    "tasks_500": ("Légende", "Compléter 500 tâches"),
    "streak_3": ("Régulier", "3 jours consécutifs"),
    "streak_7": ("Semaine parfaite", "7 jours consécutifs"),
    "streak_14": ("Deux semaines", "14 jours consécutifs"),
    "streak_30": ("Mois parfait", "30 jours consécutifs"),
    "weight_king": ("Roi de la pénibilité", "Compléter une tâche de poids 10"),
    "early_bird": ("Matinal", "Compléter une tâche avant 8h"),
    "night_owl": ("Noctambule", "Compléter une tâche après 22h"),
}


# --- Edition completions ---

@router.put("/completions/{completion_id}")
async def update_completion(
    completion_id: UUID,
    body: CompletionUpdate,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Modifie une completion (completed_by et/ou completed_at). Scope foyer via JOIN tasks."""
    result = await session.execute(
        select(TaskCompletion)
        .join(Task, Task.id == TaskCompletion.task_id)
        .where(
            TaskCompletion.id == completion_id,
            TaskCompletion.deleted_at.is_(None),
            Task.foyer_id == current_foyer_uuid(current),
        )
    )
    comp = result.scalar_one_or_none()
    if not comp:
        raise HTTPException(status_code=404, detail="Completion introuvable")

    old_data = {
        "completed_by": str(comp.completed_by),
        "completed_at": comp.completed_at.isoformat(),
    }

    updates = body.model_dump(exclude_unset=True)
    for key, val in updates.items():
        setattr(comp, key, val)
    comp.version += 1

    await record_change(
        session, "task_completions", comp.id, "UPDATE", current["user_id"],
        foyer_id=current["foyer_id"],
        old_data=old_data,
        new_data={k: str(v) for k, v in updates.items()},
    )
    await session.commit()
    await session.refresh(comp)
    return {
        "id": str(comp.id),
        "task_id": str(comp.task_id),
        "completed_by": str(comp.completed_by),
        "completed_at": comp.completed_at.isoformat(),
        "points_earned": comp.points_earned,
        "notes": comp.notes,
    }


# --- Stats ---

async def _load_foyer_absent_dates(
    session: AsyncSession,
    foyer_uuid: UUID,
    today: date,
) -> set[date]:
    """Home freeze v2 : retourne les dates `confirmed_absent` du foyer si
    `home_freeze_enabled = True`, sinon set vide. Utilise par les stats pour
    exclure les completions tombant sur des jours d'absence — coherent avec
    le freeze (un user qui declare absent puis logue une completion ne doit
    pas la voir comptee dans ses stats hebdo/mensuelles/totales).
    """
    foyer_r = await session.execute(
        select(Foyer.home_freeze_enabled).where(Foyer.id == foyer_uuid)
    )
    if not bool(foyer_r.scalar() or False):
        return set()
    # Charge tout l'historique des absences confirmees (table petite : 1 ligne
    # par jour foyer-level).
    return await get_absent_dates(session, foyer_uuid, date(2000, 1, 1), today)


@router.get("/stats", response_model=list[UserStats])
async def get_stats(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Statistiques par utilisateur : points et taches (semaine/mois/total). Scope foyer via JOIN tasks.

    Home freeze v2 : exclut les completions tombant sur un jour
    `confirmed_absent` du foyer (si freeze actif).
    """
    from app.models.user_foyer import UserFoyer

    now = datetime.now(timezone.utc)
    week_ago = now - timedelta(days=7)
    month_ago = now - timedelta(days=30)
    foyer_uuid = current_foyer_uuid(current)

    absent_dates = await _load_foyer_absent_dates(session, foyer_uuid, now.date())
    absent_filter = (
        [func.date(TaskCompletion.completed_at).notin_(list(absent_dates))]
        if absent_dates else []
    )

    # Users du foyer actif uniquement (via UserFoyer join)
    users_result = await session.execute(
        select(User)
        .join(UserFoyer, UserFoyer.user_id == User.id)
        .where(UserFoyer.foyer_id == foyer_uuid)
        .order_by(User.username)
    )
    users = users_result.scalars().all()

    stats = []
    for user in users:
        # Total (scope foyer)
        total_result = await session.execute(
            select(
                func.count(TaskCompletion.id),
                func.coalesce(func.sum(TaskCompletion.points_earned), 0)
            )
            .join(Task, Task.id == TaskCompletion.task_id)
            .where(
                TaskCompletion.completed_by == user.id,
                TaskCompletion.deleted_at.is_(None),
                Task.foyer_id == foyer_uuid,
                *absent_filter,
            )
        )
        total_row = total_result.one()

        # Semaine (scope foyer)
        week_result = await session.execute(
            select(
                func.count(TaskCompletion.id),
                func.coalesce(func.sum(TaskCompletion.points_earned), 0)
            )
            .join(Task, Task.id == TaskCompletion.task_id)
            .where(
                TaskCompletion.completed_by == user.id,
                TaskCompletion.deleted_at.is_(None),
                TaskCompletion.completed_at >= week_ago,
                Task.foyer_id == foyer_uuid,
                *absent_filter,
            )
        )
        week_row = week_result.one()

        # Mois (scope foyer)
        month_result = await session.execute(
            select(
                func.count(TaskCompletion.id),
                func.coalesce(func.sum(TaskCompletion.points_earned), 0)
            )
            .join(Task, Task.id == TaskCompletion.task_id)
            .where(
                TaskCompletion.completed_by == user.id,
                TaskCompletion.deleted_at.is_(None),
                TaskCompletion.completed_at >= month_ago,
                Task.foyer_id == foyer_uuid,
                *absent_filter,
            )
        )
        month_row = month_result.one()

        stats.append(UserStats(
            user_id=user.id,
            display_name=user.display_name,
            points_total=int(total_row[1]),
            tasks_total=int(total_row[0]),
            points_week=int(week_row[1]),
            tasks_week=int(week_row[0]),
            points_month=int(month_row[1]),
            tasks_month=int(month_row[0]),
        ))

    return stats


# --- Leaderboard ---

@router.get("/leaderboard", response_model=list[LeaderboardEntry])
async def get_leaderboard(
    period: str = "week",
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Leaderboard par periode : week, month, all. Scope foyer via JOIN tasks.

    Home freeze v2 : exclut les completions tombant sur un jour
    `confirmed_absent` du foyer (si freeze actif).
    """
    now = datetime.now(timezone.utc)
    foyer_uuid = current_foyer_uuid(current)
    if period == "week":
        cutoff = now - timedelta(days=7)
    elif period == "month":
        cutoff = now - timedelta(days=30)
    else:
        cutoff = datetime(2000, 1, 1, tzinfo=timezone.utc)

    absent_dates = await _load_foyer_absent_dates(session, foyer_uuid, now.date())
    absent_filter = (
        [func.date(TaskCompletion.completed_at).notin_(list(absent_dates))]
        if absent_dates else []
    )

    result = await session.execute(
        select(
            TaskCompletion.completed_by,
            func.coalesce(func.sum(TaskCompletion.points_earned), 0).label("points"),
            func.count(TaskCompletion.id).label("tasks_count"),
        )
        .join(Task, Task.id == TaskCompletion.task_id)
        .where(
            TaskCompletion.deleted_at.is_(None),
            TaskCompletion.completed_at >= cutoff,
            Task.foyer_id == foyer_uuid,
            *absent_filter,
        ).group_by(TaskCompletion.completed_by).order_by(
            func.sum(TaskCompletion.points_earned).desc()
        )
    )
    rows = result.all()

    # Charger les noms et fair-share ratios (users et fair-share scope foyer)
    from app.models.user_foyer import UserFoyer
    users_result = await session.execute(
        select(User)
        .join(UserFoyer, UserFoyer.user_id == User.id)
        .where(UserFoyer.foyer_id == foyer_uuid)
    )
    user_names = {u.id: u.display_name for u in users_result.scalars().all()}

    from app.models.fair_share import FairShareConfig
    fs_result = await session.execute(
        select(FairShareConfig).where(FairShareConfig.foyer_id == foyer_uuid)
    )
    fs_ratios = {r.user_id: r.target_ratio for r in fs_result.scalars().all()}

    # Construire les entries avec score normalise
    raw_entries = []
    for row in rows:
        uid = row[0]
        pts = int(row[1])
        ratio = fs_ratios.get(uid, 0.5)
        # Score normalise : points / ratio → si chacun fait sa part, score egal
        norm = pts / ratio if ratio > 0 else pts
        raw_entries.append({
            "user_id": uid,
            "display_name": user_names.get(uid, "?"),
            "points": pts,
            "normalized_score": round(norm, 1),
            "tasks_count": int(row[2]),
        })

    # Ajouter les users sans completions
    seen = {e["user_id"] for e in raw_entries}
    for uid, name in user_names.items():
        if uid not in seen:
            raw_entries.append({
                "user_id": uid, "display_name": name,
                "points": 0, "normalized_score": 0.0, "tasks_count": 0,
            })

    # Trier par score normalise (le vrai classement equitable)
    raw_entries.sort(key=lambda x: x["normalized_score"], reverse=True)

    entries = []
    for rank, e in enumerate(raw_entries, 1):
        entries.append(LeaderboardEntry(rank=rank, **e))

    return entries


# --- Streaks ---

def _compute_user_streak(
    days: list[date],
    today: date,
    absent_set: set[date],
) -> tuple[int, int, list[date], list[date]]:
    """Calcule (current_streak, best_streak, current_run, best_run).

    `days` = dates distinctes avec >=1 completion, triees descendant.
    `absent_set` = dates explicitement confirmed_absent du foyer.

    Un jour de absent_set est neutre : il ne casse pas le streak mais ne
    s'incremente pas non plus (semantique "jours actifs" — le compteur
    affiche le vrai nombre de jours d'activite, jamais menteur). Les deux
    listes de dates renvoyees incluent les jours absents PONTES (pour la
    continuite visuelle du liseré cote client) ; les compteurs entiers, eux,
    ne comptent que les jours d'activite (semantique inchangee).
    """
    days_set = set(days)

    # Curseur "current" : tolerance hier si rien aujourd'hui ni absent aujourd'hui.
    cursor = today
    if cursor not in days_set and cursor not in absent_set:
        if (cursor - timedelta(days=1)) in days_set:
            cursor = cursor - timedelta(days=1)

    current = 0
    current_run: list[date] = []
    while True:
        if cursor in days_set:
            current += 1
            current_run.append(cursor)
            cursor -= timedelta(days=1)
        elif cursor in absent_set:
            current_run.append(cursor)  # ponte : continuite visuelle
            cursor -= timedelta(days=1)
        else:
            break

    # Best : on itere les completions desc, en autorisant un gap >1 si tous
    # les jours intermediaires sont absent_set (ponte).
    best = 0
    best_run: list[date] = []
    if days:
        run = 1
        run_dates: list[date] = [days[0]]
        for i in range(1, len(days)):
            gap = (days[i - 1] - days[i]).days
            if gap == 1:
                run += 1
                run_dates.append(days[i])
                continue
            intermediate = {days[i - 1] - timedelta(days=k) for k in range(1, gap)}
            if intermediate.issubset(absent_set):
                run += 1
                run_dates.extend(sorted(intermediate))
                run_dates.append(days[i])
            else:
                if run > best:
                    best, best_run = run, list(run_dates)
                run = 1
                run_dates = [days[i]]
        if run > best:
            best, best_run = run, list(run_dates)

    return current, best, sorted(set(current_run)), sorted(set(best_run))


@router.get("/streaks", response_model=list[StreakInfo])
async def get_streaks(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Calcule les streaks (jours consecutifs avec au moins 1 tache) pour chaque user du foyer."""
    from app.models.user_foyer import UserFoyer

    foyer_uuid = current_foyer_uuid(current)
    users_result = await session.execute(
        select(User)
        .join(UserFoyer, UserFoyer.user_id == User.id)
        .where(UserFoyer.foyer_id == foyer_uuid)
        .order_by(User.username)
    )
    users = users_result.scalars().all()

    streaks = []
    today = date.today()
    for user in users:
        # Recuperer les dates distinctes de completion (scope foyer)
        day_col = func.date_trunc('day', TaskCompletion.completed_at).label('d')
        result = await session.execute(
            select(day_col)
            .join(Task, Task.id == TaskCompletion.task_id)
            .where(
                TaskCompletion.completed_by == user.id,
                TaskCompletion.deleted_at.is_(None),
                Task.foyer_id == foyer_uuid,
            ).group_by(day_col).order_by(day_col.desc())
        )
        days = [row[0].date() if hasattr(row[0], 'date') else row[0] for row in result.all()]

        # Charger les jours d'absence couvrant la fenetre du calcul (de la
        # plus vieille completion a aujourd'hui) pour neutraliser les vacances.
        oldest = days[-1] if days else today
        absent_set = await get_absent_dates(session, foyer_uuid, oldest, today)
        current, best, current_run, best_run = _compute_user_streak(
            days, today, absent_set,
        )

        streaks.append(StreakInfo(
            user_id=user.id,
            display_name=user.display_name,
            current_streak=current,
            best_streak=best,
            current_run=current_run,
            best_run=best_run,
        ))

    return streaks


# --- Badges ---

@router.get("/badges", response_model=list[BadgeInfo])
async def get_badges(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Liste tous les badges avec statut (gagne ou pas) pour l'user courant dans le foyer actif."""
    user_id = UUID(current["user_id"])
    foyer_uuid = current_foyer_uuid(current)

    # Badges gagnes (scope foyer)
    result = await session.execute(
        select(Badge).where(
            Badge.user_id == user_id,
            Badge.foyer_id == foyer_uuid,
        )
    )
    earned = {b.badge_type: b.earned_at for b in result.scalars().all()}

    # Verifier et attribuer automatiquement les badges non encore gagnes (scope foyer)
    total_result = await session.execute(
        select(func.count(TaskCompletion.id))
        .join(Task, Task.id == TaskCompletion.task_id)
        .where(
            TaskCompletion.completed_by == user_id,
            TaskCompletion.deleted_at.is_(None),
            Task.foyer_id == foyer_uuid,
        )
    )
    total_tasks = total_result.scalar() or 0

    # Badges a verifier
    task_thresholds = {"first_task": 1, "tasks_10": 10, "tasks_50": 50, "tasks_100": 100, "tasks_500": 500}
    for badge_type, threshold in task_thresholds.items():
        if badge_type not in earned and total_tasks >= threshold:
            badge = Badge(user_id=user_id, foyer_id=foyer_uuid, badge_type=badge_type)
            session.add(badge)
            earned[badge_type] = datetime.now(timezone.utc)

    # Streaks (scope foyer) — meme logique que GET /streaks (gel des absences).
    day_col = func.date_trunc('day', TaskCompletion.completed_at).label('d')
    streaks_result = await session.execute(
        select(day_col)
        .join(Task, Task.id == TaskCompletion.task_id)
        .where(
            TaskCompletion.completed_by == user_id,
            TaskCompletion.deleted_at.is_(None),
            Task.foyer_id == foyer_uuid,
        ).group_by(day_col).order_by(day_col.desc())
    )
    days = [row[0].date() if hasattr(row[0], 'date') else row[0] for row in streaks_result.all()]
    today = date.today()
    oldest = days[-1] if days else today
    absent_set = await get_absent_dates(session, foyer_uuid, oldest, today)
    _, best_streak, _, _ = _compute_user_streak(days, today, absent_set)

    streak_thresholds = {"streak_3": 3, "streak_7": 7, "streak_14": 14, "streak_30": 30}
    for badge_type, threshold in streak_thresholds.items():
        if badge_type not in earned and best_streak >= threshold:
            badge = Badge(user_id=user_id, foyer_id=foyer_uuid, badge_type=badge_type)
            session.add(badge)
            earned[badge_type] = datetime.now(timezone.utc)

    await session.commit()

    # Construire la reponse
    badges = []
    for badge_type, (label, desc) in BADGE_CATALOG.items():
        badges.append(BadgeInfo(
            badge_type=badge_type,
            label=label,
            description=desc,
            earned_at=earned.get(badge_type),
            earned=badge_type in earned,
        ))

    return badges


# --- Graphiques : course de points + frequence par jour de semaine ---

class PointsTimelinePoint(BaseModel):
    date: str
    cumulative: int


class PointsTimelineUser(BaseModel):
    user_id: UUID
    display_name: str
    points: list[PointsTimelinePoint]
    # Total de la periode precedente symetrique (pour delta W vs W-1, M vs M-1).
    # None pour period=all (pas de "all-1" qui ait du sens).
    points_previous: int | None = None


class PointsTimelineResponse(BaseModel):
    period: str   # week | month | all
    bucket: str   # day | week | month
    users: list[PointsTimelineUser]


@router.get("/stats/points-timeline", response_model=PointsTimelineResponse)
async def get_points_timeline(
    period: str = "week",
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Course de points cumules par utilisateur sur la periode choisie.

    - week  : 7 jours, granularite jour
    - month : 30 jours, granularite jour
    - all   : depuis la 1ere completion du foyer ; granularite jour si <= 90j,
              semaine entre 90j et 365j, mois si > 365j.

    Toutes les series partagent la meme grille temporelle (memes buckets) pour
    superposer les courbes proprement cote client.

    `points_previous` : total brut sur la periode precedente symetrique, pour
    afficher un "+/-X% vs sem precedente" cote client. None pour `all`.
    """
    from app.models.user_foyer import UserFoyer

    foyer_uuid = current_foyer_uuid(current)
    today = datetime.now(timezone.utc).date()

    # Determiner la fenetre + granularite + fenetre comparatif precedent.
    # `prev_start_dt` / `prev_end_dt` : fenetre [start, end[ pour W-1/M-1.
    prev_start_dt: datetime | None = None
    prev_end_dt: datetime | None = None
    if period == "week":
        start_date = today - timedelta(days=6)
        bucket = "day"
        prev_end_dt = datetime.combine(start_date, datetime.min.time(), tzinfo=timezone.utc)
        prev_start_dt = prev_end_dt - timedelta(days=7)
    elif period == "month":
        start_date = today - timedelta(days=29)
        bucket = "day"
        prev_end_dt = datetime.combine(start_date, datetime.min.time(), tzinfo=timezone.utc)
        prev_start_dt = prev_end_dt - timedelta(days=30)
    else:  # all
        first_result = await session.execute(
            select(func.min(TaskCompletion.completed_at))
            .join(Task, Task.id == TaskCompletion.task_id)
            .where(
                TaskCompletion.deleted_at.is_(None),
                Task.foyer_id == foyer_uuid,
            )
        )
        first_dt = first_result.scalar()
        start_date = first_dt.date() if first_dt is not None else today
        span_days = (today - start_date).days
        if span_days > 365:
            bucket = "month"
        elif span_days > 90:
            bucket = "week"
        else:
            bucket = "day"

    # Users du foyer
    users_result = await session.execute(
        select(User)
        .join(UserFoyer, UserFoyer.user_id == User.id)
        .where(UserFoyer.foyer_id == foyer_uuid)
        .order_by(User.username)
    )
    users = users_result.scalars().all()

    # Completions du foyer dans la fenetre
    start_dt = datetime.combine(start_date, datetime.min.time(), tzinfo=timezone.utc)
    comps_result = await session.execute(
        select(
            TaskCompletion.completed_by,
            TaskCompletion.completed_at,
            TaskCompletion.points_earned,
        )
        .join(Task, Task.id == TaskCompletion.task_id)
        .where(
            TaskCompletion.deleted_at.is_(None),
            TaskCompletion.completed_at >= start_dt,
            Task.foyer_id == foyer_uuid,
        )
    )
    rows = comps_result.all()

    # Grille de buckets : journee, semaine (lundi), ou mois (1er du mois).
    buckets: list[date] = []
    if bucket == "day":
        d = start_date
        while d <= today:
            buckets.append(d)
            d += timedelta(days=1)
    elif bucket == "week":
        d = start_date - timedelta(days=start_date.weekday())
        end = today - timedelta(days=today.weekday())
        while d <= end:
            buckets.append(d)
            d += timedelta(days=7)
    else:  # month
        d = date(start_date.year, start_date.month, 1)
        end = date(today.year, today.month, 1)
        while d <= end:
            buckets.append(d)
            # Mois suivant : addition manuelle (pas de timedelta de mois).
            if d.month == 12:
                d = date(d.year + 1, 1, 1)
            else:
                d = date(d.year, d.month + 1, 1)

    # Assure au moins un bucket pour les courbes vides (foyer fraichement cree)
    if not buckets:
        buckets = [today]

    # Index bucket -> position (pour O(1) lookup)
    idx_of: dict[date, int] = {b: i for i, b in enumerate(buckets)}

    # Pre-charger les totals de la periode precedente symetrique (1 query
    # groupee par user) — utilise par le footer "+/-X% vs sem precedente".
    prev_totals_by_user: dict[UUID, int] = {}
    if prev_start_dt is not None and prev_end_dt is not None:
        prev_q = (
            select(
                TaskCompletion.completed_by,
                func.coalesce(func.sum(TaskCompletion.points_earned), 0),
            )
            .join(Task, Task.id == TaskCompletion.task_id)
            .where(
                TaskCompletion.deleted_at.is_(None),
                TaskCompletion.completed_at >= prev_start_dt,
                TaskCompletion.completed_at < prev_end_dt,
                Task.foyer_id == foyer_uuid,
            )
            .group_by(TaskCompletion.completed_by)
        )
        for row in (await session.execute(prev_q)).all():
            prev_totals_by_user[row[0]] = int(row[1])

    # Cumul par user
    user_results: list[PointsTimelineUser] = []
    for user in users:
        per_bucket = [0] * len(buckets)
        for completed_by, completed_at, pts in rows:
            if completed_by != user.id:
                continue
            comp_date = completed_at.date()
            if bucket == "day":
                pos = idx_of.get(comp_date)
            elif bucket == "week":
                wk = comp_date - timedelta(days=comp_date.weekday())
                pos = idx_of.get(wk)
            else:  # month
                mo = date(comp_date.year, comp_date.month, 1)
                pos = idx_of.get(mo)
            if pos is not None:
                per_bucket[pos] += int(pts)

        cum = 0
        points_list = []
        for i, b in enumerate(buckets):
            cum += per_bucket[i]
            points_list.append(PointsTimelinePoint(
                date=b.isoformat(),
                cumulative=cum,
            ))
        user_results.append(PointsTimelineUser(
            user_id=user.id,
            display_name=user.display_name,
            points=points_list,
            points_previous=(
                prev_totals_by_user.get(user.id, 0)
                if prev_start_dt is not None
                else None
            ),
        ))

    return PointsTimelineResponse(period=period, bucket=bucket, users=user_results)


class WeekdayFrequencyUser(BaseModel):
    user_id: UUID
    display_name: str
    counts: list[int]   # 7 entrees, lundi=0..dimanche=6
    points: list[int]


class WeekdayFrequencyResponse(BaseModel):
    users: list[WeekdayFrequencyUser]


@router.get("/stats/weekday-frequency", response_model=WeekdayFrequencyResponse)
async def get_weekday_frequency(
    period: str = "all",
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Frequence des taches faites par jour de semaine, par utilisateur.

    Index 0=lundi..6=dimanche (postgres isodow renvoie 1..7 -> on remappe).
    Filtre par periode : `week` (7j), `month` (30j), `three_months` (90j),
    `all` (defaut, pas de filtre).
    """
    from app.models.user_foyer import UserFoyer

    foyer_uuid = current_foyer_uuid(current)

    # Cutoff selon la periode demandee.
    now = datetime.now(timezone.utc)
    cutoff: datetime | None
    if period == "week":
        cutoff = now - timedelta(days=7)
    elif period == "month":
        cutoff = now - timedelta(days=30)
    elif period == "three_months":
        cutoff = now - timedelta(days=90)
    else:
        cutoff = None  # all

    users_result = await session.execute(
        select(User)
        .join(UserFoyer, UserFoyer.user_id == User.id)
        .where(UserFoyer.foyer_id == foyer_uuid)
        .order_by(User.username)
    )
    users = users_result.scalars().all()

    dow = func.extract("isodow", TaskCompletion.completed_at).label("dow")
    base_q = (
        select(
            TaskCompletion.completed_by,
            dow,
            func.count(TaskCompletion.id).label("cnt"),
            func.coalesce(func.sum(TaskCompletion.points_earned), 0).label("pts"),
        )
        .join(Task, Task.id == TaskCompletion.task_id)
        .where(
            TaskCompletion.deleted_at.is_(None),
            Task.foyer_id == foyer_uuid,
        )
    )
    if cutoff is not None:
        base_q = base_q.where(TaskCompletion.completed_at >= cutoff)
    base_q = base_q.group_by(TaskCompletion.completed_by, dow)
    result = await session.execute(base_q)
    rows = result.all()

    user_results: list[WeekdayFrequencyUser] = []
    for user in users:
        counts = [0] * 7
        points = [0] * 7
        for completed_by, day_of_week, cnt, pts in rows:
            if completed_by != user.id:
                continue
            idx = int(day_of_week) - 1   # 1..7 -> 0..6
            if 0 <= idx < 7:
                counts[idx] = int(cnt)
                points[idx] = int(pts)
        user_results.append(WeekdayFrequencyUser(
            user_id=user.id,
            display_name=user.display_name,
            counts=counts,
            points=points,
        ))

    return WeekdayFrequencyResponse(users=user_results)


# --- Frequence par categorie ---

class CategoryFrequencyEntry(BaseModel):
    category_id: UUID | None  # None = sans categorie
    category_name: str
    counts: list[int]  # parallele a `users` (meme ordre)
    total: int


class CategoryFrequencyUser(BaseModel):
    user_id: UUID
    display_name: str


class CategoryFrequencyResponse(BaseModel):
    users: list[CategoryFrequencyUser]
    categories: list[CategoryFrequencyEntry]  # top 8, triees par total desc


@router.get("/stats/category-frequency", response_model=CategoryFrequencyResponse)
async def get_category_frequency(
    period: str = "all",
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Repartition des completions par categorie, breakdown per-user.

    Top 8 categories par nombre total de completions, avec un breakdown
    `counts` parallele a la liste `users`. Les taches sans categorie sont
    rangees dans une categorie virtuelle "Sans categorie" (category_id=None).

    Periode (default `all`) : week, month, three_months, all — meme semantique
    que /stats/weekday-frequency.
    """
    from app.models.category import Category
    from app.models.user_foyer import UserFoyer

    foyer_uuid = current_foyer_uuid(current)

    # Cutoff
    now = datetime.now(timezone.utc)
    cutoff: datetime | None
    if period == "week":
        cutoff = now - timedelta(days=7)
    elif period == "month":
        cutoff = now - timedelta(days=30)
    elif period == "three_months":
        cutoff = now - timedelta(days=90)
    else:
        cutoff = None

    # Users du foyer (ordre stable)
    users_result = await session.execute(
        select(User)
        .join(UserFoyer, UserFoyer.user_id == User.id)
        .where(UserFoyer.foyer_id == foyer_uuid)
        .order_by(User.username)
    )
    users = list(users_result.scalars().all())
    user_index = {u.id: i for i, u in enumerate(users)}

    # Counts par (user_id, category_id_or_NULL).
    base_q = (
        select(
            TaskCompletion.completed_by,
            Task.category_id,
            func.count(TaskCompletion.id).label("cnt"),
        )
        .join(Task, Task.id == TaskCompletion.task_id)
        .where(
            TaskCompletion.deleted_at.is_(None),
            Task.foyer_id == foyer_uuid,
        )
    )
    if cutoff is not None:
        base_q = base_q.where(TaskCompletion.completed_at >= cutoff)
    base_q = base_q.group_by(TaskCompletion.completed_by, Task.category_id)
    rows = (await session.execute(base_q)).all()

    # Aggreger par categorie : counts[i] = count pour users[i].
    cat_acc: dict[UUID | None, list[int]] = {}
    cat_total: dict[UUID | None, int] = {}
    for completed_by, category_id, cnt in rows:
        if completed_by not in user_index:
            continue
        if category_id not in cat_acc:
            cat_acc[category_id] = [0] * len(users)
            cat_total[category_id] = 0
        cat_acc[category_id][user_index[completed_by]] += int(cnt)
        cat_total[category_id] += int(cnt)

    # Charger les noms des categories utilisees
    cat_ids = [cid for cid in cat_acc.keys() if cid is not None]
    cat_names: dict[UUID, str] = {}
    if cat_ids:
        cat_result = await session.execute(
            select(Category.id, Category.name).where(Category.id.in_(cat_ids))
        )
        cat_names = {row[0]: row[1] for row in cat_result.all()}

    # Trier par total desc, top 8.
    ordered = sorted(cat_acc.items(), key=lambda kv: cat_total[kv[0]], reverse=True)[:8]

    entries: list[CategoryFrequencyEntry] = []
    for cid, counts in ordered:
        name = cat_names.get(cid, "?") if cid is not None else "Sans categorie"
        entries.append(CategoryFrequencyEntry(
            category_id=cid,
            category_name=name,
            counts=counts,
            total=cat_total[cid],
        ))

    return CategoryFrequencyResponse(
        users=[
            CategoryFrequencyUser(user_id=u.id, display_name=u.display_name)
            for u in users
        ],
        categories=entries,
    )


# --- Retard historique du foyer ---

class RetardTimelinePoint(BaseModel):
    date: str
    minutes: int
    task_count: int


class RetardTimelineResponse(BaseModel):
    points: list[RetardTimelinePoint]


@router.get("/stats/retard-timeline", response_model=RetardTimelineResponse)
async def get_retard_timeline(
    period: str = "all",
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Historique du retard du foyer (taches en commun uniquement).

    Lit la table `foyer_retard_snapshots`. Si vide pour ce foyer (1ere
    consultation, juste apres deploy), declenche un backfill in-line depuis
    la 1ere creation de tache. Le job APScheduler 03h15 UTC garde la table
    a jour ensuite.

    Parametre `period` :
    - `all` (defaut) : tout l'historique. Triche assumee — on coupe la
      phase de creation initiale du backlog (1er pic des minutes dans les
      7 premiers jours) pour que la courbe parte du sommet.
    - `month` : 30 derniers jours, sans trim. Utilise par la carte Home
      qui veut une vue glissante recente, fidele a la realite.

    La granularite est journaliere ; le client decide d'echantillonner si
    la serie est tres longue.
    """
    from app.models.foyer_retard_snapshot import FoyerRetardSnapshot
    from app.services.retard_history import (
        backfill_retard, has_snapshots,
    )

    foyer_uuid = current_foyer_uuid(current)

    # Auto-backfill lazy si table vide pour ce foyer.
    if not await has_snapshots(session, foyer_uuid):
        await backfill_retard(session, foyer_uuid)
        await session.commit()

    base_q = (
        select(
            FoyerRetardSnapshot.date,
            FoyerRetardSnapshot.total_minutes,
            FoyerRetardSnapshot.task_count,
        )
        .where(FoyerRetardSnapshot.foyer_id == foyer_uuid)
        .order_by(FoyerRetardSnapshot.date.asc())
    )
    if period == "month":
        cutoff_date = datetime.now(timezone.utc).date() - timedelta(days=30)
        base_q = base_q.where(FoyerRetardSnapshot.date >= cutoff_date)
    rows = (await session.execute(base_q)).all()

    # Trim de la phase ascendante bruitee (creation initiale du backlog OU
    # debut de fenetre coincide avec un pic). On applique le meme trim pour
    # `all` et `month` : sur `month`, si le foyer est recent (< 30 jours),
    # les 1ers points de la fenetre sont la phase de creation et bruitent
    # autant la lecture que pour `all`.
    if len(rows) >= 2:
        # Heuristique : on cherche le 1er pic des minutes dans les 7
        # premiers jours seulement, pour ne pas masquer un vrai pic de retard
        # apparu plus tard (procrastination, vacances, etc.). Apres 7 jours,
        # toute ascension est du backlog reel a montrer, pas de la creation.
        INITIAL_CREATION_WINDOW_DAYS = 7
        head = rows[: min(INITIAL_CREATION_WINDOW_DAYS, len(rows))]
        max_minutes_head = max(r[1] for r in head)
        peak_idx = next(i for i, r in enumerate(head) if r[1] == max_minutes_head)
        # Garde-fou : si le pic-tete est le dernier point disponible, pas
        # de trim (sinon courbe a 1 point => "Pas encore de donnees").
        if peak_idx <= len(rows) - 2:
            rows = rows[peak_idx:]

    return RetardTimelineResponse(points=[
        RetardTimelinePoint(
            date=d.isoformat(),
            minutes=int(m),
            task_count=int(c),
        )
        for d, m, c in rows
    ])


# --- Historique audit ---

@router.get("/completions/{completion_id}/history")
async def completion_history(
    completion_id: UUID,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Historique des modifications d'une completion. Scope foyer."""
    from app.models.sync import ChangeLog
    foyer_uuid = current_foyer_uuid(current)
    result = await session.execute(
        select(ChangeLog).where(
            ChangeLog.table_name == "task_completions",
            ChangeLog.row_id == completion_id,
            ChangeLog.foyer_id == foyer_uuid,
        ).order_by(ChangeLog.changed_at.desc())
    )
    logs = result.scalars().all()

    # Charger les noms d'utilisateurs
    user_ids = {log.changed_by for log in logs if log.changed_by}
    user_names = {}
    if user_ids:
        users = await session.execute(select(User).where(User.id.in_(user_ids)))
        user_names = {u.id: u.display_name for u in users.scalars().all()}

    return [
        {
            "operation": log.operation,
            "changed_by": user_names.get(log.changed_by, str(log.changed_by)) if log.changed_by else None,
            "changed_at": log.changed_at.isoformat(),
            "old_data": log.old_data,
            "new_data": log.new_data,
        }
        for log in logs
    ]


# --- Historique global ---

# Champs "techniques" ignores dans les UPDATE tasks : leur presence seule
# ne doit pas generer un event d'historique.
_TASK_TECHNICAL_FIELDS = {
    "updated_at",
    "version",
    "last_completed_at",
    "next_due_at",
}

# Libelles francais des champs metier pour les descriptions.
_TASK_FIELD_LABELS = {
    "title": "titre",
    "description": "description",
    "category_id": "categorie",
    "assigned_to_ids": "attribution",
    "points_value": "points",
    "weight": "poids",
    "recurrence_rule": "recurrence",
    "tolerance": "tolerance",
    "deleted_at": "suppression",
}


def _format_minutes(points: int) -> str:
    """Formate N points (1pt = 5min) en 'XhYY' ou 'Xmin'."""
    minutes = points * 5
    if minutes >= 60:
        return f"{minutes // 60}h{minutes % 60:02d}"
    return f"{minutes}min"


# Mapping famille -> event_type retournes (cf. _build_history). Sert au
# filtre cote client (3 chips de famille) -> liste plate de event_type
# cote API. Garde une seule source de verite pour la projection.
HISTORY_FAMILY_TYPES: dict[str, set[str]] = {
    "completions": {"completion", "completion_edited", "completion_deleted"},
    "votes": {"affinity", "effort"},
    "modifications": {"task_created", "task_updated", "task_deleted", "category_changed"},
}

# Mapping inverse : event_type -> source (utilise pour decider quelles
# queries fetcher en amont quand l'user ne demande qu'un sous-ensemble).
_TYPES_FROM_COMPLETIONS = {"completion"}
_TYPES_FROM_AFFINITIES = {"affinity"}
_TYPES_FROM_EFFORTS = {"effort"}
_TYPES_FROM_TASKS_LOG = {"task_created", "task_updated", "task_deleted"}
_TYPES_FROM_COMPLETIONS_LOG = {"completion_edited", "completion_deleted"}
_TYPES_FROM_CATEGORIES_LOG = {"category_changed"}


def _format_recurrence_rule(rule: dict | None) -> str | None:
    """Rend une recurrence_rule lisible pour l'historique. None si vide."""
    if not rule:
        return None
    rtype = rule.get("type")
    if rtype == "interval":
        n = rule.get("every_n_days")
        return f"tous les {n}j" if n else "interval"
    if rtype == "monthly_variable":
        return "variable par mois"
    if rtype == "uncertain":
        return "incertain"
    if rtype == "none":
        return "ponctuelle"
    return rtype or "?"


async def _build_history(
    session: AsyncSession,
    foyer_uuid: UUID,
    limit: int,
    before: datetime | None,
    actor_id: UUID | None = None,
    event_types: set[str] | None = None,
    since: datetime | None = None,
    q: str | None = None,
) -> list[dict]:
    """Construit le feed d'historique, filtrable par actor + type + periode +
    recherche titre. Scope foyer.

    - Deduplique les completions consecutives (meme task + meme user dans la
      fenetre derivee de la recurrence), pour masquer les doubles validations.
    - Filtre les UPDATE tasks qui ne touchent que des champs techniques.
    - `actor_id` : ne retourne que les events de cet utilisateur.
    - `event_types` : ensemble de types `event["type"]` a garder (None = tous).
      Les sources non concernees ne sont pas fetchees du tout.
    - `since` : borne basse de date (>=) appliquee a chaque source.
    - `q` : recherche dans le titre de tache (ILIKE %q%). Les events sans
      tache associee (category_changed) sont exclus quand `q` est fourni.
    - Toutes les sources sont filtrees par foyer : soit directement
      (change_log.foyer_id), soit via un JOIN sur tasks.
    """
    from app.models.sync import ChangeLog
    from app.models.task_affinity import TaskAffinity
    from app.models.task_effort import TaskEffort

    # Determiner quelles sources fetcher selon event_types.
    if event_types is None:
        fetch_completions = True
        fetch_affinities = True
        fetch_efforts = True
        log_tables: list[str] = ["tasks", "task_completions", "categories"]
    else:
        fetch_completions = bool(event_types & _TYPES_FROM_COMPLETIONS)
        fetch_affinities = bool(event_types & _TYPES_FROM_AFFINITIES)
        fetch_efforts = bool(event_types & _TYPES_FROM_EFFORTS)
        log_tables = []
        if event_types & _TYPES_FROM_TASKS_LOG:
            log_tables.append("tasks")
        if event_types & _TYPES_FROM_COMPLETIONS_LOG:
            log_tables.append("task_completions")
        # categories n'a pas de titre de tache : exclu quand `q` est fourni.
        if event_types & _TYPES_FROM_CATEGORIES_LOG and not q:
            log_tables.append("categories")

    # Si l'user fournit q sans event_types restreint, on exclut quand meme
    # les categories (pas de titre a matcher).
    if q and event_types is None:
        log_tables = [t for t in log_tables if t != "categories"]

    # Token de recherche normalise (case-insensitive via ILIKE).
    q_pattern = f"%{q}%" if q else None

    # On sur-echantillonne chaque source puis on tronque apres tri global,
    # pour eviter de perdre des events quand une source domine.
    fetch_limit = limit * 2

    # Charger les users du foyer actif uniquement.
    from app.models.user_foyer import UserFoyer
    users_result = await session.execute(
        select(User)
        .join(UserFoyer, UserFoyer.user_id == User.id)
        .where(UserFoyer.foyer_id == foyer_uuid)
    )
    user_names = {u.id: u.display_name for u in users_result.scalars().all()}

    def _actor(user_id: UUID | None) -> dict | None:
        if user_id is None:
            return None
        return {"id": str(user_id), "name": user_names.get(user_id, "?")}

    events: list[dict] = []

    # --- 1. Completions (avec recurrence pour dedup, groupees par collectif) ---
    # Note pagination : toutes les lignes d'un collectif partagent completed_at
    # exactement (cree dans la meme transaction). Le cursor `before` < ne split
    # donc jamais un collectif entre deux pages.
    if fetch_completions:
        comp_query = select(TaskCompletion, Task.title, Task.recurrence_rule).join(
            Task, Task.id == TaskCompletion.task_id
        ).where(
            TaskCompletion.deleted_at.is_(None),
            Task.foyer_id == foyer_uuid,
        )
        if before is not None:
            comp_query = comp_query.where(TaskCompletion.completed_at < before)
        if since is not None:
            comp_query = comp_query.where(TaskCompletion.completed_at >= since)
        if actor_id is not None:
            # Inclure les collectifs ou actor_id est UN des participants : on
            # ramene toutes les lignes du collectif pour reconstruire la liste
            # complete (sinon le grouping aurait une participation amputee).
            comp_query = comp_query.where(
                or_(
                    TaskCompletion.completed_by == actor_id,
                    TaskCompletion.collective_id.in_(
                        select(TaskCompletion.collective_id).where(
                            TaskCompletion.completed_by == actor_id,
                            TaskCompletion.collective_id.is_not(None),
                            TaskCompletion.deleted_at.is_(None),
                        )
                    ),
                )
            )
        if q_pattern is not None:
            comp_query = comp_query.where(Task.title.ilike(q_pattern))
        comp_query = comp_query.order_by(TaskCompletion.completed_at.desc()).limit(fetch_limit)
        comp_rows = (await session.execute(comp_query)).all()
    else:
        comp_rows = []

    # Grouping par collective_id (None = solo, cle unique par row).
    comp_groups: dict[object, list[tuple[TaskCompletion, str, dict | None]]] = {}
    group_order: list[object] = []
    for comp, title, rule in comp_rows:
        key: object = comp.collective_id if comp.collective_id is not None else ("solo", comp.id)
        if key not in comp_groups:
            comp_groups[key] = []
            group_order.append(key)
        comp_groups[key].append((comp, title, rule))

    # Dedup anti-doublon : key = (task_id, frozenset(participants)). Permet
    # qu'un solo et un collectif sur la meme tache dans la meme fenetre ne
    # se masquent pas mutuellement.
    seen_last: dict[tuple[UUID, frozenset[UUID]], datetime] = {}

    for key in group_order:
        rows = comp_groups[key]
        head, title, rule = rows[0]
        participants = [r[0].completed_by for r in rows]
        seen_key = (head.task_id, frozenset(participants))
        last_seen = seen_last.get(seen_key)
        if last_seen is not None:
            gap = min_completion_gap_seconds(rule)
            if (last_seen - head.completed_at).total_seconds() < gap:
                # Doublon dans la fenetre : on skip.
                continue
        seen_last[seen_key] = head.completed_at

        is_collective = head.collective_id is not None and len(rows) >= 2
        if not is_collective:
            actor = _actor(head.completed_by)
            who = actor["name"] if actor else "?"
            events.append({
                "type": "completion",
                "date": head.completed_at,
                "description": f"{who} a complete : {title} (+{head.points_earned} pts)",
                "user": who,
                "actor": actor,
                "task": {"id": str(head.task_id), "title": title},
                "meta": {"points": head.points_earned},
            })
        else:
            participant_names = [user_names.get(uid, "?") for uid in participants]
            joined = join_with_amp(participant_names)
            pts = head.points_earned  # toutes les lignes partagent les memes points
            events.append({
                "type": "completion",
                "date": head.completed_at,
                "description": f"{joined} ont fait : {title} (+{pts} pts chacun)",
                "user": joined,
                "actor": _actor(head.completed_by),  # le recorder
                "task": {"id": str(head.task_id), "title": title},
                "meta": {
                    "points": pts,
                    "collective_id": str(head.collective_id),
                    "participant_ids": [str(p) for p in participants],
                    "participant_names": participant_names,
                },
            })

    # --- 2. Affinites (seulement celles qui ont reellement change : created_at != updated_at) ---
    if fetch_affinities:
        aff_query = select(TaskAffinity, Task.title).join(
            Task, Task.id == TaskAffinity.task_id
        ).where(
            TaskAffinity.updated_at > TaskAffinity.created_at,
            Task.foyer_id == foyer_uuid,
        )
        if before is not None:
            aff_query = aff_query.where(TaskAffinity.updated_at < before)
        if since is not None:
            aff_query = aff_query.where(TaskAffinity.updated_at >= since)
        if actor_id is not None:
            aff_query = aff_query.where(TaskAffinity.user_id == actor_id)
        if q_pattern is not None:
            aff_query = aff_query.where(Task.title.ilike(q_pattern))
        aff_query = aff_query.order_by(TaskAffinity.updated_at.desc()).limit(fetch_limit)
        aff_rows = (await session.execute(aff_query)).all()
    else:
        aff_rows = []

    for aff, title in aff_rows:
        actor = _actor(aff.user_id)
        who = actor["name"] if actor else "?"
        events.append({
            "type": "affinity",
            "date": aff.updated_at,
            "description": f"{who} a note l'affinite de '{title}' a {aff.score}/10",
            "user": who,
            "actor": actor,
            "task": {"id": str(aff.task_id), "title": title},
            "meta": {"score": aff.score},
        })

    # --- 3. Efforts (idem : filtre created_at != updated_at) ---
    if fetch_efforts:
        eff_query = select(TaskEffort, Task.title).join(
            Task, Task.id == TaskEffort.task_id
        ).where(
            TaskEffort.updated_at > TaskEffort.created_at,
            Task.foyer_id == foyer_uuid,
        )
        if before is not None:
            eff_query = eff_query.where(TaskEffort.updated_at < before)
        if since is not None:
            eff_query = eff_query.where(TaskEffort.updated_at >= since)
        if actor_id is not None:
            eff_query = eff_query.where(TaskEffort.user_id == actor_id)
        if q_pattern is not None:
            eff_query = eff_query.where(Task.title.ilike(q_pattern))
        eff_query = eff_query.order_by(TaskEffort.updated_at.desc()).limit(fetch_limit)
        eff_rows = (await session.execute(eff_query)).all()
    else:
        eff_rows = []

    for eff, title in eff_rows:
        actor = _actor(eff.user_id)
        who = actor["name"] if actor else "?"
        events.append({
            "type": "effort",
            "date": eff.updated_at,
            "description": f"{who} a estime l'effort de '{title}' a {_format_minutes(eff.points)}",
            "user": who,
            "actor": actor,
            "task": {"id": str(eff.task_id), "title": title},
            "meta": {"points": eff.points, "minutes": eff.points * 5},
        })

    # --- 4. Modifications via change_log (scope foyer direct) ---
    if log_tables:
        log_query = select(ChangeLog).where(
            ChangeLog.table_name.in_(log_tables),
            ChangeLog.operation.in_(["INSERT", "UPDATE", "DELETE"]),
            ChangeLog.foyer_id == foyer_uuid,
        )
        if before is not None:
            log_query = log_query.where(ChangeLog.changed_at < before)
        if since is not None:
            log_query = log_query.where(ChangeLog.changed_at >= since)
        if actor_id is not None:
            log_query = log_query.where(ChangeLog.changed_by == actor_id)
        log_query = log_query.order_by(ChangeLog.changed_at.desc()).limit(fetch_limit * 2)
        logs = (await session.execute(log_query)).scalars().all()
    else:
        logs = []

    # Pour dedup vote/recurrence : chaque vote de frequence (set_frequency)
    # ecrit a la fois un task_frequency_votes/VOTE ET un tasks/UPDATE avec
    # new_data == {"recurrence_rule": ...}. On masque le tasks/UPDATE quand
    # un freq_vote du meme user existe a ±2s. Meme logique que
    # /tasks/{id}/history (cf. tasks.py:317-340).
    # (changed_at, changed_by, row_id=task_id) — le row_id est essentiel pour
    # ne dedupliquer que le tasks/UPDATE de la MEME tache (sinon plusieurs
    # actions enchainees sur des taches differentes se masquent mutuellement).
    freq_votes_ts: list[tuple[datetime, UUID | None, UUID]] = []
    if "tasks" in log_tables:
        fv_query = select(
            ChangeLog.changed_at, ChangeLog.changed_by, ChangeLog.row_id
        ).where(
            ChangeLog.table_name == "task_frequency_votes",
            ChangeLog.foyer_id == foyer_uuid,
        )
        if before is not None:
            fv_query = fv_query.where(ChangeLog.changed_at < before)
        if since is not None:
            fv_query = fv_query.where(ChangeLog.changed_at >= since)
        if actor_id is not None:
            fv_query = fv_query.where(ChangeLog.changed_by == actor_id)
        fv_query = fv_query.order_by(ChangeLog.changed_at.desc()).limit(fetch_limit * 2)
        freq_votes_ts = [(r[0], r[1], r[2]) for r in (await session.execute(fv_query)).all()]

    # Eager-load des titres de taches mentionnes dans les logs (plus de N+1).
    task_ids_needed: set[UUID] = set()
    completion_ids_needed: set[UUID] = set()
    for log in logs:
        if log.table_name == "tasks":
            task_ids_needed.add(log.row_id)
        elif log.table_name == "task_completions":
            completion_ids_needed.add(log.row_id)

    task_titles: dict[UUID, str] = {}
    if task_ids_needed:
        rows = await session.execute(
            select(Task.id, Task.title).where(Task.id.in_(task_ids_needed))
        )
        task_titles = {row[0]: row[1] for row in rows.all()}

        # Fallback pour les taches absentes de `tasks` (hard-deleted) :
        # recuperer le dernier titre connu depuis change_log en cherchant un
        # INSERT/UPDATE precedent qui contient `new_data.title`.
        missing = task_ids_needed - set(task_titles.keys())
        if missing:
            fallback_rows = await session.execute(
                select(ChangeLog.row_id, ChangeLog.new_data, ChangeLog.changed_at)
                .where(
                    ChangeLog.table_name == "tasks",
                    ChangeLog.row_id.in_(missing),
                    ChangeLog.new_data["title"].astext.isnot(None),
                )
                .order_by(ChangeLog.changed_at.desc())
            )
            seen: set[UUID] = set()
            for row_id, new_data, _changed_at in fallback_rows.all():
                if row_id in seen:
                    continue
                seen.add(row_id)
                title = (new_data or {}).get("title")
                if title:
                    task_titles[row_id] = f"{title} (supprimee)"

    completion_task_titles: dict[UUID, tuple[UUID, str]] = {}
    if completion_ids_needed:
        rows = await session.execute(
            select(TaskCompletion.id, Task.id, Task.title)
            .join(Task, Task.id == TaskCompletion.task_id)
            .where(TaskCompletion.id.in_(completion_ids_needed))
        )
        completion_task_titles = {row[0]: (row[1], row[2]) for row in rows.all()}

    q_lower = q.lower() if q else None

    for log in logs:
        actor = _actor(log.changed_by)
        who = actor["name"] if actor else "?"

        if log.table_name == "tasks":
            title = task_titles.get(log.row_id, "?")
            task_info = {"id": str(log.row_id), "title": title}

            if log.operation == "INSERT":
                event_type = "task_created"
                desc = f"{who} a cree la tache '{title}'"
                meta = {}
            elif log.operation == "DELETE":
                event_type = "task_deleted"
                desc = f"{who} a supprime la tache '{title}'"
                meta = {}
            else:  # UPDATE
                # Dedup vote/recurrence : un tasks/UPDATE qui ne touche QUE
                # recurrence_rule et qui partage le MEME timestamp transaction
                # qu'un task_frequency_votes/VOTE du meme user sur la meme
                # tache = meme transaction, evenement loggue 2x. On masque
                # le tasks/UPDATE pour ne garder que le vote sous-jacent.
                # Egalite stricte volontaire : Postgres func.now() retourne
                # le timestamp de debut de transaction, donc deux record_change
                # dans la meme transaction partagent le meme changed_at. Un
                # changement de type fait dans une autre transaction (meme a
                # 50 ms d'ecart) ne sera donc pas dedup a tort.
                if (
                    log.new_data
                    and set(log.new_data.keys()) == {"recurrence_rule"}
                    and any(
                        log.changed_by == fv[1]
                        and log.row_id == fv[2]
                        and log.changed_at == fv[0]
                        for fv in freq_votes_ts
                    )
                ):
                    continue

                # Filtrer les champs techniques : si rien ne reste, on saute.
                raw_fields = list((log.new_data or {}).keys())
                meaningful = [f for f in raw_fields if f not in _TASK_TECHNICAL_FIELDS]
                if not meaningful:
                    continue
                labels = [_TASK_FIELD_LABELS.get(f, f) for f in meaningful]
                event_type = "task_updated"
                # Detail enrichi pour recurrence_rule : "tous les 5j -> tous les 7j".
                detail_extra = ""
                if "recurrence_rule" in meaningful:
                    old_rec = _format_recurrence_rule((log.old_data or {}).get("recurrence_rule"))
                    new_rec = _format_recurrence_rule((log.new_data or {}).get("recurrence_rule"))
                    if old_rec and new_rec and old_rec != new_rec:
                        detail_extra = f" : {old_rec} → {new_rec}"
                    elif new_rec:
                        detail_extra = f" : {new_rec}"
                desc = f"{who} a modifie la tache '{title}' ({', '.join(labels)}){detail_extra}"
                meta = {"fields": meaningful}

            # Filtres a grain fin (event_types + q) pour les events partageant
            # la meme table change_log.
            if event_types is not None and event_type not in event_types:
                continue
            if q_lower is not None and q_lower not in title.lower():
                continue

            events.append({
                "type": event_type,
                "date": log.changed_at,
                "description": desc,
                "user": who,
                "actor": actor,
                "task": task_info,
                "meta": meta,
            })

        elif log.table_name == "task_completions":
            # Les INSERT sont deja couverts par la branche "completion" ci-dessus.
            # On ne garde ici que les UPDATE (edition manuelle) et DELETE.
            if log.operation == "INSERT":
                continue
            info = completion_task_titles.get(log.row_id)
            if info is None:
                title = "?"
                task_info = None
            else:
                task_id, title = info
                task_info = {"id": str(task_id), "title": title}
            event_type = "completion_edited" if log.operation == "UPDATE" else "completion_deleted"
            action = "modifie" if log.operation == "UPDATE" else "supprime"

            if event_types is not None and event_type not in event_types:
                continue
            if q_lower is not None and q_lower not in title.lower():
                continue

            events.append({
                "type": event_type,
                "date": log.changed_at,
                "description": f"{who} a {action} une completion de '{title}'",
                "user": who,
                "actor": actor,
                "task": task_info,
                "meta": {},
            })

        elif log.table_name == "categories":
            # Les category_changed n'ont pas de titre de tache : pas de filtre
            # `q` applicable (deja exclu en amont si `q` fourni).
            action_map = {"INSERT": "cree", "UPDATE": "modifie", "DELETE": "supprime"}
            action = action_map.get(log.operation, log.operation.lower())
            events.append({
                "type": "category_changed",
                "date": log.changed_at,
                "description": f"{who} a {action} une categorie",
                "user": who,
                "actor": actor,
                "task": None,
                "meta": {"operation": log.operation},
            })

    # Tri global par datetime decroissant puis troncature au limit demande.
    events.sort(key=lambda e: e["date"], reverse=True)
    events = events[:limit]

    # Serialiser les dates en ISO pour la reponse JSON.
    for e in events:
        e["date"] = e["date"].isoformat()
    return events


@router.get("/history")
async def global_history(
    limit: int = 50,
    before: datetime | None = None,
    since: datetime | None = None,
    family: list[str] = Query(default=[]),
    actor_id: UUID | None = None,
    q: str | None = None,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Historique global du foyer actif (completions, votes, modifications).

    - `limit` : nombre max d'evenements (defaut 50).
    - `before` : curseur de pagination. Si fourni, ne retourne que les events
      strictement anterieurs a cette date (scroll vers le passe).
    - `since` : borne basse de date (>=). Combine avec `before` pour une
      fenetre temporelle (cette semaine, ce mois, etc.).
    - `family` : multi-valued. Un ou plusieurs parmi {completions, votes,
      modifications}. Defaut vide = toutes les familles. Les familles sont
      mappees vers les `event_type` cibles via `HISTORY_FAMILY_TYPES`.
    - `actor_id` : ne retourne que les events de cet utilisateur (filtre
      par membre du foyer). Defaut = tous.
    - `q` : recherche dans le titre de tache (ILIKE %q%, case-insensitive).
      Les events sans tache (category_changed) sont exclus quand `q` est
      fourni.
    """
    # Mapper les familles -> set de event_types. Famille inconnue = ignoree.
    if family:
        event_types: set[str] | None = set()
        for fam in family:
            event_types.update(HISTORY_FAMILY_TYPES.get(fam, set()))
        # Si aucune famille connue n'a ete fournie -> set vide -> aucun
        # resultat (semantique stricte, plutot que de tout retourner).
    else:
        event_types = None

    return await _build_history(
        session,
        foyer_uuid=current_foyer_uuid(current),
        limit=limit,
        before=before,
        actor_id=actor_id,
        event_types=event_types,
        since=since,
        q=q,
    )


@router.get("/users/{user_id}/history")
async def user_history(
    user_id: UUID,
    limit: int = 50,
    before: datetime | None = None,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Historique personnel d'un utilisateur dans le foyer actif."""
    return await _build_history(
        session,
        foyer_uuid=current_foyer_uuid(current),
        limit=limit,
        before=before,
        actor_id=user_id,
    )


# --- Stats long-terme (v0.31, item roadmap "Stats long-terme et par dimension") ---


class LongTermDay(BaseModel):
    """Aggregat journalier : completions + points + minutes estimees."""
    day: date
    completions: int
    points: int
    estimated_minutes: int


class LongTermRoom(BaseModel):
    """Aggregat par piece sur la periode demandee. room_id null = taches
    sans piece rattachee."""
    room_id: UUID | None
    room_name: str
    completions: int
    points: int
    estimated_minutes: int


class LongTermResponse(BaseModel):
    period: str  # 'month' | 'quarter' | 'year'
    days: int    # taille de la fenetre en jours
    completions_per_day: list[LongTermDay]
    by_room: list[LongTermRoom]


@router.get("/stats/long-term", response_model=LongTermResponse)
async def long_term_stats(
    period: str = Query(default="quarter", pattern="^(month|quarter|year)$"),
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Aggregate stats sur une fenetre longue : evolution journaliere +
    repartition par piece. Utilise pour les graphiques analytiques de la
    roadmap "Stats long-terme et par dimension".

    Le `estimated_minutes` est calcule a partir du consensus effort (avg
    de task_efforts.points * 5min). Tasks sans vote effort comptent 0
    minute (on ne devine pas).
    """
    from app.models.room import Room
    from app.models.task_effort import TaskEffort

    foyer_uuid = current_foyer_uuid(current)
    days_window = {"month": 30, "quarter": 90, "year": 365}[period]
    now = datetime.now(timezone.utc)
    since = now - timedelta(days=days_window)

    # Home freeze v2 : exclure les completions tombant sur jours absents.
    absent_dates = await _load_foyer_absent_dates(session, foyer_uuid, now.date())
    absent_filter = (
        [func.date(TaskCompletion.completed_at).notin_(list(absent_dates))]
        if absent_dates else []
    )

    # Subquery : effort moyen par task (en minutes : avg points * 5).
    effort_subq = (
        select(
            TaskEffort.task_id.label("task_id"),
            (func.avg(TaskEffort.points) * 5).label("avg_minutes"),
        )
        .group_by(TaskEffort.task_id)
        .subquery()
    )

    # 1. Completions per day.
    days_result = await session.execute(
        select(
            func.date(TaskCompletion.completed_at).label("day"),
            func.count(TaskCompletion.id),
            func.coalesce(func.sum(TaskCompletion.points_earned), 0),
            func.coalesce(func.sum(effort_subq.c.avg_minutes), 0),
        )
        .join(Task, Task.id == TaskCompletion.task_id)
        .outerjoin(effort_subq, effort_subq.c.task_id == Task.id)
        .where(
            Task.foyer_id == foyer_uuid,
            TaskCompletion.deleted_at.is_(None),
            TaskCompletion.completed_at >= since,
            *absent_filter,
        )
        .group_by("day")
        .order_by("day")
    )
    completions_per_day = [
        LongTermDay(
            day=row[0],
            completions=int(row[1] or 0),
            points=int(row[2] or 0),
            estimated_minutes=int(row[3] or 0),
        )
        for row in days_result.all()
    ]

    # 2. By room (LEFT JOIN pour inclure les taches sans room_id).
    rooms_result = await session.execute(
        select(
            Room.id,
            Room.name,
            func.count(TaskCompletion.id),
            func.coalesce(func.sum(TaskCompletion.points_earned), 0),
            func.coalesce(func.sum(effort_subq.c.avg_minutes), 0),
        )
        .select_from(TaskCompletion)
        .join(Task, Task.id == TaskCompletion.task_id)
        .outerjoin(Room, Room.id == Task.room_id)
        .outerjoin(effort_subq, effort_subq.c.task_id == Task.id)
        .where(
            Task.foyer_id == foyer_uuid,
            TaskCompletion.deleted_at.is_(None),
            TaskCompletion.completed_at >= since,
            *absent_filter,
        )
        .group_by(Room.id, Room.name)
        .order_by(func.count(TaskCompletion.id).desc())
    )
    by_room = [
        LongTermRoom(
            room_id=row[0],
            room_name=row[1] or "Sans piece",
            completions=int(row[2] or 0),
            points=int(row[3] or 0),
            estimated_minutes=int(row[4] or 0),
        )
        for row in rooms_result.all()
    ]

    return LongTermResponse(
        period=period,
        days=days_window,
        completions_per_day=completions_per_day,
        by_room=by_room,
    )


# --- Totaux du couple (v0.31, item roadmap "Stats globales du couple") ---


class CoupleTotalsResponse(BaseModel):
    """Totaux foyer-level (somme de tous les members) sur 3 fenetres +
    streak collectif. Affiche dans UserProfileDialog en complement des
    stats personnelles. Item roadmap : "combien le foyer a fait au total".
    """
    foyer_total_completions: int
    foyer_total_points: int
    foyer_month_completions: int
    foyer_month_points: int
    foyer_week_completions: int
    foyer_week_points: int
    # Nombre de jours consecutifs (jusqu'a aujourd'hui ou hier inclus)
    # avec >=1 completion par n'importe quel member. 0 si rien aujourd'hui
    # ni hier.
    couple_streak_days: int


@router.get("/stats/couple-totals", response_model=CoupleTotalsResponse)
async def couple_totals(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Totaux foyer cumules (week/month/all) + streak couple.

    Home freeze v2 : exclut les completions tombant sur un jour
    `confirmed_absent` du foyer (si freeze actif).
    """
    foyer_uuid = current_foyer_uuid(current)
    now = datetime.now(timezone.utc)
    week_ago = now - timedelta(days=7)
    month_ago = now - timedelta(days=30)

    absent_dates = await _load_foyer_absent_dates(session, foyer_uuid, now.date())
    absent_filter = (
        [func.date(TaskCompletion.completed_at).notin_(list(absent_dates))]
        if absent_dates else []
    )

    # Helper : sum + count de completions sur une periode (None = pas de
    # filtre temporel = "tout").
    async def _agg(since: datetime | None) -> tuple[int, int]:
        stmt = (
            select(
                func.count(TaskCompletion.id),
                func.coalesce(func.sum(TaskCompletion.points_earned), 0),
            )
            .join(Task, Task.id == TaskCompletion.task_id)
            .where(
                Task.foyer_id == foyer_uuid,
                TaskCompletion.deleted_at.is_(None),
                *absent_filter,
            )
        )
        if since is not None:
            stmt = stmt.where(TaskCompletion.completed_at >= since)
        result = await session.execute(stmt)
        c, p = result.one()
        return int(c or 0), int(p or 0)

    total_c, total_p = await _agg(None)
    month_c, month_p = await _agg(month_ago)
    week_c, week_p = await _agg(week_ago)

    # Couple streak : jours consecutifs avec >=1 completion par n'importe
    # quel member. On prend les dates distinctes (UTC) descendant et on
    # compte tant que la difference avec la precedente est <=1j.
    distinct_days_result = await session.execute(
        select(func.date(TaskCompletion.completed_at).label("d"))
        .join(Task, Task.id == TaskCompletion.task_id)
        .where(
            Task.foyer_id == foyer_uuid,
            TaskCompletion.deleted_at.is_(None),
        )
        .group_by("d")
        .order_by(func.date(TaskCompletion.completed_at).desc())
        .limit(400)  # ~1 an max, suffisant pour un streak realiste
    )
    days_desc: list[date] = [r[0] for r in distinct_days_result.all()]
    today = now.date()
    yesterday = today - timedelta(days=1)
    streak = 0
    if days_desc:
        # Acceptable de partir d'aujourd'hui OU d'hier (pour ne pas casser
        # le streak juste parce qu'on n'a pas encore fait de tache aujourd'hui).
        if days_desc[0] == today or days_desc[0] == yesterday:
            streak = 1
            prev = days_desc[0]
            for d in days_desc[1:]:
                if (prev - d).days == 1:
                    streak += 1
                    prev = d
                else:
                    break

    return CoupleTotalsResponse(
        foyer_total_completions=total_c,
        foyer_total_points=total_p,
        foyer_month_completions=month_c,
        foyer_month_points=month_p,
        foyer_week_completions=week_c,
        foyer_week_points=week_p,
        couple_streak_days=streak,
    )


# --- Resume hebdomadaire (v0.31, item roadmap "Statistiques hebdomadaires") ---


class WeeklySummaryUser(BaseModel):
    user_id: UUID
    display_name: str
    completions_count: int
    points_earned: int


class WeeklySummaryTopTask(BaseModel):
    task_id: UUID
    title: str
    count: int


class WeeklySummaryResponse(BaseModel):
    """Resume de fin de semaine : un foyer + N users sur la fenetre lundi-
    dimanche (UTC). Permet de presenter un bilan court a l'app le dimanche
    soir / lundi matin.
    """
    week_start: date  # lundi 00:00 UTC
    week_end: date    # dimanche 23:59 UTC
    week_offset: int  # 0 = semaine en cours, -1 = semaine derniere, etc.
    per_user: list[WeeklySummaryUser]
    top_tasks: list[WeeklySummaryTopTask]
    foyer_total_completions: int
    foyer_total_points: int


@router.get("/stats/weekly-summary", response_model=WeeklySummaryResponse)
async def weekly_summary(
    week_offset: int = Query(default=0, ge=-52, le=0),
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Resume per-user + foyer pour une semaine UTC (lundi -> dimanche).

    week_offset = 0 (defaut) : semaine en cours
    week_offset = -1         : semaine derniere
    week_offset jusqu'a -52  : il y a un an

    Ne retourne pas de week_offset > 0 (futur) — les bornes Query l'empechent.

    Home freeze v2 : exclut les completions tombant sur un jour
    `confirmed_absent` du foyer (si freeze actif).
    """
    from app.models.user_foyer import UserFoyer

    foyer_uuid = current_foyer_uuid(current)

    # Calcul des bornes de la semaine demandee (UTC, ISO 8601 : lundi=0).
    today = datetime.now(timezone.utc).date()
    monday_of_week = today - timedelta(days=today.weekday())
    target_monday = monday_of_week + timedelta(weeks=week_offset)
    target_sunday = target_monday + timedelta(days=6)

    week_start_dt = datetime.combine(target_monday, datetime.min.time(), tzinfo=timezone.utc)
    week_end_dt = datetime.combine(target_sunday, datetime.max.time(), tzinfo=timezone.utc)

    absent_dates = await _load_foyer_absent_dates(session, foyer_uuid, today)
    absent_filter = (
        [func.date(TaskCompletion.completed_at).notin_(list(absent_dates))]
        if absent_dates else []
    )

    # Per-user : completions + points sur la fenetre, scope foyer.
    # On part de la liste users du foyer (left join sur completions pour
    # inclure les users a 0).
    per_user_result = await session.execute(
        select(
            User.id,
            User.display_name,
            func.count(TaskCompletion.id),
            func.coalesce(func.sum(TaskCompletion.points_earned), 0),
        )
        .join(UserFoyer, UserFoyer.user_id == User.id)
        .outerjoin(
            TaskCompletion,
            (TaskCompletion.completed_by == User.id)
            & (TaskCompletion.deleted_at.is_(None))
            & (TaskCompletion.completed_at >= week_start_dt)
            & (TaskCompletion.completed_at <= week_end_dt)
            & (
                func.date(TaskCompletion.completed_at).notin_(list(absent_dates))
                if absent_dates else True
            ),
        )
        .outerjoin(
            Task,
            (Task.id == TaskCompletion.task_id)
            & (Task.foyer_id == foyer_uuid),
        )
        .where(
            UserFoyer.foyer_id == foyer_uuid,
            UserFoyer.left_at.is_(None),
            User.deleted_at.is_(None),
        )
        .group_by(User.id, User.display_name)
        .order_by(func.count(TaskCompletion.id).desc(), User.display_name)
    )
    per_user = [
        WeeklySummaryUser(
            user_id=row[0],
            display_name=row[1],
            completions_count=row[2] or 0,
            points_earned=row[3] or 0,
        )
        for row in per_user_result.all()
    ]

    # Top tasks (par nombre de completions) sur la fenetre.
    top_tasks_result = await session.execute(
        select(
            Task.id,
            Task.title,
            func.count(TaskCompletion.id),
        )
        .join(TaskCompletion, TaskCompletion.task_id == Task.id)
        .where(
            Task.foyer_id == foyer_uuid,
            TaskCompletion.deleted_at.is_(None),
            TaskCompletion.completed_at >= week_start_dt,
            TaskCompletion.completed_at <= week_end_dt,
            *absent_filter,
        )
        .group_by(Task.id, Task.title)
        .order_by(func.count(TaskCompletion.id).desc())
        .limit(5)
    )
    top_tasks = [
        WeeklySummaryTopTask(
            task_id=row[0],
            title=row[1],
            count=row[2] or 0,
        )
        for row in top_tasks_result.all()
    ]

    # Totaux foyer = somme des per_user (deja scope foyer).
    total_completions = sum(u.completions_count for u in per_user)
    total_points = sum(u.points_earned for u in per_user)

    return WeeklySummaryResponse(
        week_start=target_monday,
        week_end=target_sunday,
        week_offset=week_offset,
        per_user=per_user,
        top_tasks=top_tasks,
        foyer_total_completions=total_completions,
        foyer_total_points=total_points,
    )
