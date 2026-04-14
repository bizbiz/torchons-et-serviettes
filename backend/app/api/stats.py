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
from app.services.recurrence import min_completion_gap_seconds
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


async def _build_history(
    session: AsyncSession,
    limit: int,
    before: datetime | None,
    actor_id: UUID | None = None,
) -> list[dict]:
    """Construit le feed d'historique, filtrable par actor.

    - Deduplique les completions consecutives (meme task + meme user dans la
      fenetre derivee de la recurrence), pour masquer les doubles validations.
    - Filtre les UPDATE tasks qui ne touchent que des champs techniques.
    - Si `actor_id` est fourni, ne retourne que les events de cet utilisateur.
    """
    from app.models.sync import ChangeLog
    from app.models.task_affinity import TaskAffinity
    from app.models.task_effort import TaskEffort

    # On sur-echantillonne chaque source puis on tronque apres tri global,
    # pour eviter de perdre des events quand une source domine.
    fetch_limit = limit * 2

    # Charger tous les users en une passe (il y en a 2, pas un souci).
    users_result = await session.execute(select(User))
    user_names = {u.id: u.display_name for u in users_result.scalars().all()}

    def _actor(user_id: UUID | None) -> dict | None:
        if user_id is None:
            return None
        return {"id": str(user_id), "name": user_names.get(user_id, "?")}

    events: list[dict] = []

    # --- 1. Completions (avec recurrence pour dedup) ---
    comp_query = select(TaskCompletion, Task.title, Task.recurrence_rule).join(
        Task, Task.id == TaskCompletion.task_id
    ).where(TaskCompletion.deleted_at.is_(None))
    if before is not None:
        comp_query = comp_query.where(TaskCompletion.completed_at < before)
    if actor_id is not None:
        comp_query = comp_query.where(TaskCompletion.completed_by == actor_id)
    comp_query = comp_query.order_by(TaskCompletion.completed_at.desc()).limit(fetch_limit)

    # Dedup : pour chaque (task_id, user_id), on ne garde la completion que si
    # elle est separee de la precedente (plus recente deja vue) d'au moins la
    # fenetre anti-doublon de cette tache.
    seen_last: dict[tuple[UUID, UUID], datetime] = {}

    for comp, title, rule in (await session.execute(comp_query)).all():
        key = (comp.task_id, comp.completed_by)
        last_seen = seen_last.get(key)
        if last_seen is not None:
            gap = min_completion_gap_seconds(rule)
            if (last_seen - comp.completed_at).total_seconds() < gap:
                # Doublon dans la fenetre : on skip.
                continue
        seen_last[key] = comp.completed_at

        actor = _actor(comp.completed_by)
        who = actor["name"] if actor else "?"
        events.append({
            "type": "completion",
            "date": comp.completed_at,
            "description": f"{who} a complete : {title} (+{comp.points_earned} pts)",
            "user": who,
            "actor": actor,
            "task": {"id": str(comp.task_id), "title": title},
            "meta": {"points": comp.points_earned},
        })

    # --- 2. Affinites (seulement celles qui ont reellement change : created_at != updated_at) ---
    aff_query = select(TaskAffinity, Task.title).join(
        Task, Task.id == TaskAffinity.task_id
    ).where(TaskAffinity.updated_at > TaskAffinity.created_at)
    if before is not None:
        aff_query = aff_query.where(TaskAffinity.updated_at < before)
    if actor_id is not None:
        aff_query = aff_query.where(TaskAffinity.user_id == actor_id)
    aff_query = aff_query.order_by(TaskAffinity.updated_at.desc()).limit(fetch_limit)

    for aff, title in (await session.execute(aff_query)).all():
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
    eff_query = select(TaskEffort, Task.title).join(
        Task, Task.id == TaskEffort.task_id
    ).where(TaskEffort.updated_at > TaskEffort.created_at)
    if before is not None:
        eff_query = eff_query.where(TaskEffort.updated_at < before)
    if actor_id is not None:
        eff_query = eff_query.where(TaskEffort.user_id == actor_id)
    eff_query = eff_query.order_by(TaskEffort.updated_at.desc()).limit(fetch_limit)

    for eff, title in (await session.execute(eff_query)).all():
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

    # --- 4. Modifications via change_log ---
    log_query = select(ChangeLog).where(
        ChangeLog.table_name.in_(["tasks", "task_completions", "categories"]),
        ChangeLog.operation.in_(["INSERT", "UPDATE", "DELETE"]),
    )
    if before is not None:
        log_query = log_query.where(ChangeLog.changed_at < before)
    if actor_id is not None:
        log_query = log_query.where(ChangeLog.changed_by == actor_id)
    log_query = log_query.order_by(ChangeLog.changed_at.desc()).limit(fetch_limit * 2)
    logs = (await session.execute(log_query)).scalars().all()

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

    completion_task_titles: dict[UUID, tuple[UUID, str]] = {}
    if completion_ids_needed:
        rows = await session.execute(
            select(TaskCompletion.id, Task.id, Task.title)
            .join(Task, Task.id == TaskCompletion.task_id)
            .where(TaskCompletion.id.in_(completion_ids_needed))
        )
        completion_task_titles = {row[0]: (row[1], row[2]) for row in rows.all()}

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
                # Filtrer les champs techniques : si rien ne reste, on saute.
                raw_fields = list((log.new_data or {}).keys())
                meaningful = [f for f in raw_fields if f not in _TASK_TECHNICAL_FIELDS]
                if not meaningful:
                    continue
                labels = [_TASK_FIELD_LABELS.get(f, f) for f in meaningful]
                event_type = "task_updated"
                desc = f"{who} a modifie la tache '{title}' ({', '.join(labels)})"
                meta = {"fields": meaningful}

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
            action = "modifie" if log.operation == "UPDATE" else "supprime"
            events.append({
                "type": "completion_edited" if log.operation == "UPDATE" else "completion_deleted",
                "date": log.changed_at,
                "description": f"{who} a {action} une completion de '{title}'",
                "user": who,
                "actor": actor,
                "task": task_info,
                "meta": {},
            })

        elif log.table_name == "categories":
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
    session: AsyncSession = Depends(get_session),
    _user: dict = Depends(get_current_user),
):
    """Historique global : completions, modifications taches/categories, affinites, efforts.

    - `limit` : nombre max d'evenements a retourner (defaut 50).
    - `before` : curseur de pagination. Si fourni, ne retourne que les events
      strictement anterieurs a cette date (utile pour scroller le passe).
    """
    return await _build_history(session, limit=limit, before=before, actor_id=None)


@router.get("/users/{user_id}/history")
async def user_history(
    user_id: UUID,
    limit: int = 50,
    before: datetime | None = None,
    session: AsyncSession = Depends(get_session),
    _user: dict = Depends(get_current_user),
):
    """Historique personnel d'un utilisateur : meme format que /history filtre par acteur."""
    return await _build_history(session, limit=limit, before=before, actor_id=user_id)
