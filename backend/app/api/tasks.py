"""Routes CRUD taches."""

from __future__ import annotations

from datetime import datetime, timezone
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.models.sync import ChangeLog
from app.models.task import Task
from app.models.task_assignment import TaskAssignment
from app.models.task_frequency_vote import TaskFrequencyVote
from app.models.user import User
from app.schemas.task import TaskCreate, TaskResponse, TaskUpdate
from app.services.frequency_vote import (
    expand_interval,
    normalize_days_per_month,
    upsert_vote_and_sync_consensus,
)
from app.utils.changelog import record_change
from app.utils.scoping import current_foyer_uuid, scoped

router = APIRouter(prefix="/api/tasks", tags=["tasks"])


async def _get_assigned_ids(session: AsyncSession, task_id: UUID) -> list[UUID]:
    """Recupere les IDs des utilisateurs assignes a une tache."""
    result = await session.execute(
        select(TaskAssignment.user_id).where(TaskAssignment.task_id == task_id)
    )
    return [row[0] for row in result.all()]


async def _task_to_response(session: AsyncSession, task: Task) -> dict:
    """Construit la reponse d'une tache avec ses assignments."""
    assigned_ids = await _get_assigned_ids(session, task.id)
    return {
        "id": task.id,
        "title": task.title,
        "description": task.description,
        "category_id": task.category_id,
        "room_id": task.room_id,
        "assigned_to_ids": assigned_ids,
        "recurrence_rule": task.recurrence_rule,
        "inferred_interval_days": task.inferred_interval_days,
        "weight": task.weight,
        "tolerance": task.tolerance,
        "is_active": task.is_active,
        "is_archived": task.is_archived,
        "sort_order": task.sort_order,
        "created_at": task.created_at,
        "updated_at": task.updated_at,
    }


@router.get("", response_model=list[TaskResponse])
async def list_tasks(
    category_id: UUID | None = None,
    active_only: bool = True,
    include_archived: bool = False,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Liste des taches visibles par l'utilisateur courant, scopees par foyer.

    include_archived=false (defaut) masque les taches auto-archivees (ponctuelles
    completees depuis plus de ARCHIVE_AFTER_DAYS jours).
    """
    user_id = UUID(current["user_id"])

    # Sous-requete : taches assignees a l'utilisateur courant
    assigned_task_ids = select(TaskAssignment.task_id).where(
        TaskAssignment.user_id == user_id
    ).scalar_subquery()

    # scoped() applique deja WHERE Task.foyer_id = current foyer
    q = scoped(Task, current).where(
        Task.deleted_at.is_(None),
        Task.id.in_(assigned_task_ids),
    )
    if active_only:
        q = q.where(Task.is_active.is_(True))
    if not include_archived:
        q = q.where(Task.is_archived.is_(False))
    if category_id:
        q = q.where(Task.category_id == category_id)
    q = q.order_by(Task.sort_order, Task.created_at)

    result = await session.execute(q)
    tasks = result.scalars().all()

    return [await _task_to_response(session, t) for t in tasks]


@router.get("/{task_id}", response_model=TaskResponse)
async def get_task(
    task_id: UUID,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    result = await session.execute(
        scoped(Task, current).where(Task.id == task_id, Task.deleted_at.is_(None))
    )
    task = result.scalar_one_or_none()
    if not task:
        raise HTTPException(status_code=404, detail="Tache introuvable")
    return await _task_to_response(session, task)


@router.post("", response_model=TaskResponse, status_code=201)
async def create_task(
    body: TaskCreate,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    task = Task(
        foyer_id=current_foyer_uuid(current),
        title=body.title,
        description=body.description,
        category_id=body.category_id,
        room_id=body.room_id,
        recurrence_rule=body.recurrence_rule.model_dump() if body.recurrence_rule else None,
        weight=body.weight,
        tolerance=body.tolerance,
    )
    session.add(task)
    await session.flush()

    # Creer les assignments
    for uid in body.assigned_to_ids:
        session.add(TaskAssignment(task_id=task.id, user_id=uid))
    await session.flush()

    # Auto-vote du createur sur tout type votable (interval ou
    # monthly_variable) : la saisie initiale devient son vote, ce qui
    # garantit l'invariant "consensus = moyenne des votes" des la creation.
    if task.recurrence_rule:
        rule_type = task.recurrence_rule.get("type")
        if rule_type == "interval":
            every_n = task.recurrence_rule.get("every_n_days")
            if isinstance(every_n, int) and every_n >= 1:
                await upsert_vote_and_sync_consensus(
                    session, task, UUID(current["user_id"]),
                    expand_interval(every_n),
                    foyer_id=current["foyer_id"],
                )
        elif rule_type == "monthly_variable":
            dpm_raw = task.recurrence_rule.get("days_per_month")
            if dpm_raw:
                try:
                    dpm = normalize_days_per_month(dpm_raw)
                    await upsert_vote_and_sync_consensus(
                        session, task, UUID(current["user_id"]), dpm,
                        foyer_id=current["foyer_id"],
                    )
                except ValueError:
                    pass  # input invalide, on saute l'auto-vote

    await record_change(
        session, "tasks", task.id, "INSERT", current["user_id"],
        foyer_id=current["foyer_id"],
        new_data=body.model_dump(mode="json"),
    )
    await session.commit()
    await session.refresh(task)
    return await _task_to_response(session, task)


@router.put("/{task_id}", response_model=TaskResponse)
async def update_task(
    task_id: UUID,
    body: TaskUpdate,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    result = await session.execute(
        scoped(Task, current).where(Task.id == task_id, Task.deleted_at.is_(None))
    )
    task = result.scalar_one_or_none()
    if not task:
        raise HTTPException(status_code=404, detail="Tache introuvable")

    updates = body.model_dump(exclude_unset=True)

    # Snapshot de l'etat avant mutation (uniquement pour les champs effectivement
    # modifies). Permet a l'historique d'afficher "ancien -> nouveau".
    old_assigned_ids = await _get_assigned_ids(session, task_id)
    old_data: dict = {}
    for key in updates.keys():
        if key == "assigned_to_ids":
            old_data["assigned_to_ids"] = [str(u) for u in old_assigned_ids]
        else:
            val = getattr(task, key, None)
            if hasattr(val, "value"):  # enum (tolerance)
                val = val.value
            old_data[key] = val

    # Gerer les assignments separement
    new_assigned_ids = updates.pop("assigned_to_ids", None)
    if new_assigned_ids is not None:
        await session.execute(
            delete(TaskAssignment).where(TaskAssignment.task_id == task_id)
        )
        for uid in new_assigned_ids:
            session.add(TaskAssignment(task_id=task_id, user_id=uid))

    # Normaliser recurrence_rule en dict (Pydantic -> dict)
    if "recurrence_rule" in updates and updates["recurrence_rule"]:
        rr = updates["recurrence_rule"]
        updates["recurrence_rule"] = rr.model_dump() if hasattr(rr, "model_dump") else rr

    # Garde anti-court-circuit du systeme de vote (v0.27 : 2 types votables).
    # Pour interval ou monthly_variable, la cadence est pilotee par les votes
    # via PUT /tasks/{id}/frequency. Toute mutation directe via PUT /tasks/{id}
    # qui change la cadence et a deja des votes est rejetee. Le retour a un
    # type votable depuis un type non-votable (uncertain ou rule=None) devient
    # un vote de l'user courant.
    VOTABLE = {"interval", "monthly_variable"}
    if "recurrence_rule" in updates and updates["recurrence_rule"]:
        new_rule = updates["recurrence_rule"]
        old_rule = task.recurrence_rule or {}
        new_type = new_rule.get("type")
        old_type = old_rule.get("type")

        if new_type in VOTABLE and old_type in VOTABLE:
            # Idempotent si rule identique, sinon 409 (passer par /frequency).
            if new_rule != old_rule:
                votes_count_r = await session.execute(
                    select(func.count(TaskFrequencyVote.task_id)).where(
                        TaskFrequencyVote.task_id == task_id
                    )
                )
                votes_count = votes_count_r.scalar() or 0
                if votes_count > 0:
                    raise HTTPException(
                        status_code=409,
                        detail={
                            "code": "FREQUENCY_VOTE_LOCKED",
                            "message": "Cadence pilotee par les votes — re-vote requis via PUT /tasks/{id}/frequency",
                            "votes_count": votes_count,
                            "consensus_rule": old_rule,
                        },
                    )

        elif new_type in VOTABLE and old_type not in VOTABLE:
            # Retour a un type votable : la saisie devient le vote de l'user
            # courant, le helper recalcule le consensus avec d'eventuels
            # votes en sommeil (peu probable mais possible).
            try:
                if new_type == "interval":
                    every_n = new_rule.get("every_n_days")
                    if not (isinstance(every_n, int) and every_n >= 1):
                        raise ValueError("every_n_days manquant ou invalide")
                    dpm = expand_interval(every_n)
                else:  # monthly_variable
                    dpm = normalize_days_per_month(new_rule.get("days_per_month"))
                updates.pop("recurrence_rule")
                await upsert_vote_and_sync_consensus(
                    session, task, UUID(current["user_id"]), dpm,
                    foyer_id=current["foyer_id"],
                )
            except ValueError:
                pass  # cas pathologique, on laisse setattr ecrire tel quel

        # new_type non votable : changement libre, votes restent dormants.

    for key, val in updates.items():
        setattr(task, key, val)
    task.version += 1

    await record_change(
        session, "tasks", task.id, "UPDATE", current["user_id"],
        foyer_id=current["foyer_id"],
        old_data=old_data,
        new_data=body.model_dump(mode="json", exclude_unset=True),
    )
    await session.commit()
    await session.refresh(task)
    return await _task_to_response(session, task)


_HISTORY_TABLES = (
    "tasks",
    "task_affinities",
    "task_efforts",
    "task_frequency_votes",
)


def _event_type(table_name: str, operation: str) -> str:
    """Mappe une ligne change_log vers un type d'evenement d'historique."""
    if table_name == "task_affinities":
        return "vote_affinity"
    if table_name == "task_efforts":
        return "vote_effort"
    if table_name == "task_frequency_votes":
        return "vote_frequency"
    return "task_update"


def _fields_changed(old_data: dict | None, new_data: dict | None) -> list[str]:
    """Liste les champs modifies dans un UPDATE tasks (hors version/updated_at)."""
    if not new_data:
        return []
    ignored = {"version", "updated_at", "created_at", "id"}
    return [k for k in new_data.keys() if k not in ignored]


@router.get("/{task_id}/history")
async def get_task_history(
    task_id: UUID,
    limit: int = 100,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Retourne l'historique unifie d'une tache : votes + modifs directes.
    Lit change_log filtre par (foyer_id, table_name, row_id=task_id).
    """
    # Verifier d'abord que la tache appartient au foyer courant
    ownership_check = await session.execute(
        scoped(Task, current).where(Task.id == task_id)
    )
    if ownership_check.scalar_one_or_none() is None:
        raise HTTPException(status_code=404, detail="Tache introuvable")

    foyer_uuid = current_foyer_uuid(current)
    result = await session.execute(
        select(
            ChangeLog.id,
            ChangeLog.table_name,
            ChangeLog.operation,
            ChangeLog.changed_by,
            ChangeLog.changed_at,
            ChangeLog.old_data,
            ChangeLog.new_data,
            User.display_name,
        )
        .outerjoin(User, User.id == ChangeLog.changed_by)
        .where(
            ChangeLog.foyer_id == foyer_uuid,
            ChangeLog.row_id == task_id,
            ChangeLog.table_name.in_(_HISTORY_TABLES),
        )
        .order_by(ChangeLog.changed_at.desc())
        .limit(limit)
    )
    rows = result.all()

    # Dedoublonnage : quand un vote de frequence genere un tasks/UPDATE avec
    # new_data = {"recurrence_rule": ...} et un task_frequency_votes/VOTE
    # du meme user a ±2s, on masque le tasks/UPDATE (doublon pour l'UI).
    freq_votes_ts: list[tuple[datetime, UUID | None]] = [
        (r.changed_at, r.changed_by)
        for r in rows
        if r.table_name == "task_frequency_votes"
    ]

    events = []
    for r in rows:
        if (
            r.table_name == "tasks"
            and r.operation == "UPDATE"
            and r.new_data
            and set(r.new_data.keys()) == {"recurrence_rule"}
        ):
            skip = any(
                r.changed_by == fv[1]
                and abs((r.changed_at - fv[0]).total_seconds()) <= 2
                for fv in freq_votes_ts
            )
            if skip:
                continue

        evt_type = _event_type(r.table_name, r.operation)
        new_data = r.new_data or {}
        old_data = r.old_data or {}

        details: dict = {
            "operation": r.operation,
            "old": old_data,
            "new": new_data,
        }
        if evt_type == "vote_affinity":
            details.update(
                value=new_data.get("score"),
                consensus_before=new_data.get("consensus_before"),
                consensus_after=new_data.get("consensus_after"),
            )
        elif evt_type == "vote_effort":
            details.update(
                value=new_data.get("points"),
                consensus_before=new_data.get("consensus_before"),
                consensus_after=new_data.get("consensus_after"),
            )
        elif evt_type == "vote_frequency":
            details.update(
                value=new_data.get("every_n_days"),
                consensus_before=new_data.get("consensus_before"),
                consensus_after=new_data.get("consensus_after"),
            )
        else:
            details["fields_changed"] = _fields_changed(old_data, new_data)

        events.append({
            "id": r.id,
            "event_type": evt_type,
            "actor_user_id": str(r.changed_by) if r.changed_by else None,
            "actor_name": r.display_name or "",
            "at": r.changed_at.isoformat() if r.changed_at else None,
            "details": details,
        })

    return events


@router.delete("/{task_id}")
async def delete_task(
    task_id: UUID,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Soft delete."""
    result = await session.execute(
        scoped(Task, current).where(Task.id == task_id, Task.deleted_at.is_(None))
    )
    task = result.scalar_one_or_none()
    if not task:
        raise HTTPException(status_code=404, detail="Tache introuvable")
    task.deleted_at = datetime.now(timezone.utc)
    task.version += 1
    await record_change(
        session, "tasks", task.id, "DELETE", current["user_id"],
        foyer_id=current["foyer_id"],
    )
    await session.commit()
    return {"ok": True}
