"""Routes statistiques — points, leaderboard, streaks, badges, edition completions."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone, date
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import delete, func, select, case, extract
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.models.badge import Badge
from app.models.completion import TaskCompletion
from app.models.streak import Streak
from app.models.task import Task
from app.models.user import User
from app.utils.changelog import record_change

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


class BadgeInfo(BaseModel):
    badge_type: str
    label: str
    description: str
    earned_at: datetime | None = None
    earned: bool = False


# --- Catalogue de badges ---

BADGE_CATALOG = {
    "first_task": ("Premiere tache", "Completer sa premiere tache"),
    "tasks_10": ("Debutant", "Completer 10 taches"),
    "tasks_50": ("Habitue", "Completer 50 taches"),
    "tasks_100": ("Expert", "Completer 100 taches"),
    "tasks_500": ("Legende", "Completer 500 taches"),
    "streak_3": ("Regulier", "3 jours consecutifs"),
    "streak_7": ("Semaine parfaite", "7 jours consecutifs"),
    "streak_14": ("Deux semaines", "14 jours consecutifs"),
    "streak_30": ("Mois parfait", "30 jours consecutifs"),
    "weight_king": ("Roi de la penibilite", "Completer une tache de poids 10"),
    "early_bird": ("Matinal", "Completer une tache avant 8h"),
    "night_owl": ("Noctambule", "Completer une tache apres 22h"),
}


# --- Edition completions ---

@router.put("/completions/{completion_id}")
async def update_completion(
    completion_id: UUID,
    body: CompletionUpdate,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Modifie une completion (completed_by et/ou completed_at)."""
    result = await session.execute(
        select(TaskCompletion).where(
            TaskCompletion.id == completion_id,
            TaskCompletion.deleted_at.is_(None),
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

@router.get("/stats", response_model=list[UserStats])
async def get_stats(
    session: AsyncSession = Depends(get_session),
    _user: dict = Depends(get_current_user),
):
    """Statistiques par utilisateur : points et taches (semaine/mois/total)."""
    now = datetime.now(timezone.utc)
    week_ago = now - timedelta(days=7)
    month_ago = now - timedelta(days=30)

    users_result = await session.execute(select(User).order_by(User.username))
    users = users_result.scalars().all()

    stats = []
    for user in users:
        base_q = select(TaskCompletion).where(
            TaskCompletion.completed_by == user.id,
            TaskCompletion.deleted_at.is_(None),
        )

        # Total
        total_result = await session.execute(
            select(
                func.count(TaskCompletion.id),
                func.coalesce(func.sum(TaskCompletion.points_earned), 0)
            ).where(
                TaskCompletion.completed_by == user.id,
                TaskCompletion.deleted_at.is_(None),
            )
        )
        total_row = total_result.one()

        # Semaine
        week_result = await session.execute(
            select(
                func.count(TaskCompletion.id),
                func.coalesce(func.sum(TaskCompletion.points_earned), 0)
            ).where(
                TaskCompletion.completed_by == user.id,
                TaskCompletion.deleted_at.is_(None),
                TaskCompletion.completed_at >= week_ago,
            )
        )
        week_row = week_result.one()

        # Mois
        month_result = await session.execute(
            select(
                func.count(TaskCompletion.id),
                func.coalesce(func.sum(TaskCompletion.points_earned), 0)
            ).where(
                TaskCompletion.completed_by == user.id,
                TaskCompletion.deleted_at.is_(None),
                TaskCompletion.completed_at >= month_ago,
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
    _user: dict = Depends(get_current_user),
):
    """Leaderboard par periode : week, month, all."""
    now = datetime.now(timezone.utc)
    if period == "week":
        cutoff = now - timedelta(days=7)
    elif period == "month":
        cutoff = now - timedelta(days=30)
    else:
        cutoff = datetime(2000, 1, 1, tzinfo=timezone.utc)

    result = await session.execute(
        select(
            TaskCompletion.completed_by,
            func.coalesce(func.sum(TaskCompletion.points_earned), 0).label("points"),
            func.count(TaskCompletion.id).label("tasks_count"),
        ).where(
            TaskCompletion.deleted_at.is_(None),
            TaskCompletion.completed_at >= cutoff,
        ).group_by(TaskCompletion.completed_by).order_by(
            func.sum(TaskCompletion.points_earned).desc()
        )
    )
    rows = result.all()

    # Charger les noms et fair-share ratios
    users_result = await session.execute(select(User))
    user_names = {u.id: u.display_name for u in users_result.scalars().all()}

    from app.models.fair_share import FairShareConfig
    fs_result = await session.execute(select(FairShareConfig))
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

@router.get("/streaks", response_model=list[StreakInfo])
async def get_streaks(
    session: AsyncSession = Depends(get_session),
    _user: dict = Depends(get_current_user),
):
    """Calcule les streaks (jours consecutifs avec au moins 1 tache) pour chaque user."""
    users_result = await session.execute(select(User).order_by(User.username))
    users = users_result.scalars().all()

    streaks = []
    for user in users:
        # Recuperer les dates distinctes de completion
        day_col = func.date_trunc('day', TaskCompletion.completed_at).label('d')
        result = await session.execute(
            select(day_col).where(
                TaskCompletion.completed_by == user.id,
                TaskCompletion.deleted_at.is_(None),
            ).group_by(day_col).order_by(day_col.desc())
        )
        days = [row[0].date() if hasattr(row[0], 'date') else row[0] for row in result.all()]

        # Calculer streak courant
        current = 0
        today = date.today()
        for i, d in enumerate(days):
            expected = today - timedelta(days=i)
            if d == expected:
                current += 1
            elif i == 0 and d == today - timedelta(days=1):
                # Accepter hier si pas encore fait aujourd'hui
                current += 1
                today = today - timedelta(days=1)
            else:
                break

        # Calculer meilleur streak
        best = 0
        if days:
            run = 1
            for i in range(1, len(days)):
                if days[i - 1] - days[i] == timedelta(days=1):
                    run += 1
                else:
                    best = max(best, run)
                    run = 1
            best = max(best, run)

        streaks.append(StreakInfo(
            user_id=user.id,
            display_name=user.display_name,
            current_streak=current,
            best_streak=best,
        ))

    return streaks


# --- Badges ---

@router.get("/badges", response_model=list[BadgeInfo])
async def get_badges(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Liste tous les badges avec statut (gagne ou pas) pour l'user courant."""
    user_id = UUID(current["user_id"])

    # Badges gagnes
    result = await session.execute(
        select(Badge).where(Badge.user_id == user_id)
    )
    earned = {b.badge_type: b.earned_at for b in result.scalars().all()}

    # Verifier et attribuer automatiquement les badges non encore gagnes
    total_result = await session.execute(
        select(func.count(TaskCompletion.id)).where(
            TaskCompletion.completed_by == user_id,
            TaskCompletion.deleted_at.is_(None),
        )
    )
    total_tasks = total_result.scalar() or 0

    # Badges a verifier
    task_thresholds = {"first_task": 1, "tasks_10": 10, "tasks_50": 50, "tasks_100": 100, "tasks_500": 500}
    for badge_type, threshold in task_thresholds.items():
        if badge_type not in earned and total_tasks >= threshold:
            badge = Badge(user_id=user_id, badge_type=badge_type)
            session.add(badge)
            earned[badge_type] = datetime.now(timezone.utc)

    # Streaks
    day_col = func.date_trunc('day', TaskCompletion.completed_at).label('d')
    streaks_result = await session.execute(
        select(day_col).where(
            TaskCompletion.completed_by == user_id,
            TaskCompletion.deleted_at.is_(None),
        ).group_by(day_col).order_by(day_col.desc())
    )
    days = [row[0].date() if hasattr(row[0], 'date') else row[0] for row in streaks_result.all()]
    best_streak = 0
    if days:
        run = 1
        for i in range(1, len(days)):
            if days[i - 1] - days[i] == timedelta(days=1):
                run += 1
            else:
                best_streak = max(best_streak, run)
                run = 1
        best_streak = max(best_streak, run)

    streak_thresholds = {"streak_3": 3, "streak_7": 7, "streak_14": 14, "streak_30": 30}
    for badge_type, threshold in streak_thresholds.items():
        if badge_type not in earned and best_streak >= threshold:
            badge = Badge(user_id=user_id, badge_type=badge_type)
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


# --- Historique audit ---

@router.get("/completions/{completion_id}/history")
async def completion_history(
    completion_id: UUID,
    session: AsyncSession = Depends(get_session),
    _user: dict = Depends(get_current_user),
):
    """Historique des modifications d'une completion."""
    from app.models.sync import ChangeLog
    result = await session.execute(
        select(ChangeLog).where(
            ChangeLog.table_name == "task_completions",
            ChangeLog.row_id == completion_id,
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

@router.get("/history")
async def global_history(
    limit: int = 50,
    session: AsyncSession = Depends(get_session),
    _user: dict = Depends(get_current_user),
):
    """Historique global de toutes les actions : completions, affinites, efforts, modifications."""
    from app.models.sync import ChangeLog
    from app.models.task_affinity import TaskAffinity
    from app.models.task_effort import TaskEffort

    events = []

    # Completions recentes
    comp_result = await session.execute(
        select(TaskCompletion, Task.title).join(
            Task, Task.id == TaskCompletion.task_id
        ).where(
            TaskCompletion.deleted_at.is_(None)
        ).order_by(TaskCompletion.completed_at.desc()).limit(limit)
    )
    # Charger les noms
    users_result = await session.execute(select(User))
    user_names = {u.id: u.display_name for u in users_result.scalars().all()}

    for comp, title in comp_result.all():
        who = user_names.get(comp.completed_by, "?")
        events.append({
            "type": "completion",
            "date": comp.completed_at.isoformat(),
            "description": f"{who} a complete : {title} (+{comp.points_earned} pts)",
            "user": who,
        })

    # Affinites recentes
    aff_result = await session.execute(
        select(TaskAffinity, Task.title).join(
            Task, Task.id == TaskAffinity.task_id
        ).order_by(TaskAffinity.updated_at.desc()).limit(limit)
    )
    for aff, title in aff_result.all():
        who = user_names.get(aff.user_id, "?")
        events.append({
            "type": "affinity",
            "date": aff.updated_at.isoformat(),
            "description": f"{who} a note l'affinite de '{title}' a {aff.score}/10",
            "user": who,
        })

    # Efforts recents
    eff_result = await session.execute(
        select(TaskEffort, Task.title).join(
            Task, Task.id == TaskEffort.task_id
        ).order_by(TaskEffort.updated_at.desc()).limit(limit)
    )
    for eff, title in eff_result.all():
        who = user_names.get(eff.user_id, "?")
        minutes = eff.points * 5
        time_str = f"{minutes // 60}h{minutes % 60:02d}" if minutes >= 60 else f"{minutes}min"
        events.append({
            "type": "effort",
            "date": eff.updated_at.isoformat(),
            "description": f"{who} a estime l'effort de '{title}' a {time_str}",
            "user": who,
        })

    # Modifications (change_log) recentes
    log_result = await session.execute(
        select(ChangeLog).where(
            ChangeLog.operation == "UPDATE"
        ).order_by(ChangeLog.changed_at.desc()).limit(20)
    )
    for log in log_result.scalars().all():
        who = user_names.get(log.changed_by, "?") if log.changed_by else "?"
        events.append({
            "type": "modification",
            "date": log.changed_at.isoformat(),
            "description": f"{who} a modifie {log.table_name} ({log.operation})",
            "user": who,
        })

    # Trier par date decroissante
    events.sort(key=lambda e: e["date"], reverse=True)
    return events[:limit]
