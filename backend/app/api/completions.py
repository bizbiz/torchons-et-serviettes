"""Routes completions — marquer une tache comme faite."""

from __future__ import annotations

import math
import uuid as uuid_lib
from datetime import datetime, timedelta, timezone
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import select, desc
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.models.completion import TaskCompletion
from app.models.fair_share import FairShareConfig
from app.models.foyer import Foyer
from app.models.presence_day import PresenceDay
from app.models.task import Task
from app.models.user_foyer import UserFoyer
from app.schemas.completion import (
    CollectiveCompletionCreate,
    CollectiveCompletionResponse,
    CompletionCreate,
    CompletionPreview,
    CompletionResponse,
)
from app.services.bonus_malus import (
    apply_multiplier_to_points,
    compute_base_points_for_task,
    compute_bonus_malus_for_completion,
    resolve_stored_reason_and_mult,
)
from app.services.recurrence import (
    compute_inferred_interval_days,
    is_uncertain,
    min_completion_gap_seconds,
)
from app.services.task_flags import NO_FLAGS, resolve_flags_for_tasks
from app.services.delegations import settle_task_delegations
from app.utils.changelog import record_change
from app.utils.errors import LocalizedHTTPException
from app.utils.scoping import (
    assert_active_members, current_foyer_uuid, outsider_refusal, visible_tasks, visible_to,
)

router = APIRouter(tags=["completions"])


def _safe_uuid(value: str | None) -> UUID | None:
    """Convertit en UUID si possible, sinon retourne None."""
    if not value:
        return None
    try:
        return UUID(value)
    except (ValueError, AttributeError):
        return None


async def recompute_interval_of_task(session: AsyncSession, task_id: UUID) -> None:
    """Re-infer the interval of the task a completion was added to, removed from or moved in.

    For the paths that hold a completion, not its task: /sync INSERT, DELETE and date
    UPDATE, REST undo and date edit. Until 2026-10-17 they did not recompute, and an
    uncertain task done solo (queued, so pushed by /sync) kept a stale interval.
    Flushes first, so the change is visible to the recompute's query.
    """
    await session.flush()
    task = await session.get(Task, task_id)
    if task is not None:
        await _recompute_inferred_interval(session, task, task.foyer_id)


async def _recompute_inferred_interval(
    session: AsyncSession,
    task: Task,
    foyer_id: UUID,
) -> None:
    """Recalcule task.inferred_interval_days apres une completion (v0.14).

    Ne fait rien si la tache n'est pas de type 'uncertain'. Sinon :
    1. Charge toutes les completions de la tache (chronologique).
    2. Si la tache gele pendant les absences (via ses contextes) ET le foyer a
       home_freeze_enabled : calcule les jours absents pour chaque paire
       consecutive (Q6.2, batch via une seule query presence_days).
    3. Appelle compute_inferred_interval_days et persiste le resultat.

    Le caller doit avoir flush la nouvelle completion AVANT d'appeler cette
    fonction pour qu'elle soit visible dans la query.
    """
    if not is_uncertain(task.recurrence_rule):
        return

    comp_result = await session.execute(
        select(TaskCompletion.completed_at, TaskCompletion.collective_id)
        .where(
            TaskCompletion.task_id == task.id,
            TaskCompletion.deleted_at.is_(None),
        )
        .order_by(TaskCompletion.completed_at.asc())
    )
    # A « done together » writes one row per participant: it is ONE occurrence of the
    # task. Counted per row, two collective completions ten days apart gave the gaps
    # [0, 10, 0] and an interval of 5.05 instead of 10 (beta, 2026-10-12).
    times = []
    seen_collectives = set()
    for completed_at, collective_id in comp_result.all():
        if collective_id is not None:
            if collective_id in seen_collectives:
                continue
            seen_collectives.add(collective_id)
        times.append(completed_at)

    if len(times) < 2:
        task.inferred_interval_days = None
        task.version += 1
        return

    # Freeze check : soustrait les jours absents uniquement si la tache est
    # Gel resolu depuis les contextes ET freeze actif au niveau foyer (Q6.2).
    absent_per_gap: list[int] | None = None
    flags = (await resolve_flags_for_tasks(session, [task.id])).get(task.id, NO_FLAGS)
    requires_home = flags.freezes_when_away

    freeze_enabled = False
    if requires_home:
        foyer_result = await session.execute(
            select(Foyer.home_freeze_enabled).where(Foyer.id == foyer_id)
        )
        freeze_enabled = bool(foyer_result.scalar() or False)

    if requires_home and freeze_enabled:
        # Batch : charger tous les jours presents dans la plage globale
        global_start = times[0].date() + timedelta(days=1)
        global_end = times[-1].date()  # exclusive
        absent_per_gap = []
        if global_start < global_end:
            present_result = await session.execute(
                select(PresenceDay.date).where(
                    PresenceDay.foyer_id == foyer_id,
                    PresenceDay.date >= global_start,
                    PresenceDay.date < global_end,
                    PresenceDay.status == "confirmed_present",
                )
            )
            present_dates = {row[0] for row in present_result.all()}
            for i in range(1, len(times)):
                gs = times[i - 1].date() + timedelta(days=1)
                ge = times[i].date()
                if gs >= ge:
                    absent_per_gap.append(0)
                    continue
                total = (ge - gs).days
                present = sum(1 for d in present_dates if gs <= d < ge)
                absent_per_gap.append(total - present)
        else:
            absent_per_gap = [0] * (len(times) - 1)

    inferred = compute_inferred_interval_days(times, absent_per_gap)
    task.inferred_interval_days = inferred
    task.version += 1


async def _after_completion(
    session: AsyncSession, task: Task, foyer_id: UUID, user_id: str, completed_at: datetime,
) -> None:
    """What a REST completion changes beyond its rows: the inferred interval of an uncertain
    task, and the end of the task's « À faire aujourd'hui » delegation (services/delegations.py).
    """
    await _recompute_inferred_interval(session, task, foyer_id)
    await settle_task_delegations(session, task.id, user_id, foyer_id, completed_at)


@router.post("/api/tasks/{task_id}/complete", response_model=CompletionResponse, status_code=201)
async def complete_task(
    task_id: UUID,
    body: CompletionCreate = CompletionCreate(),
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Marque une tache comme completee (scope foyer)."""
    result = await session.execute(
        visible_tasks(current).where(Task.id == task_id, Task.deleted_at.is_(None))
    )
    task = result.scalar_one_or_none()
    if not task:
        raise LocalizedHTTPException(status_code=404, error_code="TASK_NOT_FOUND")

    completed_by = body.completed_by or UUID(current["user_id"])
    await assert_active_members(session, task.foyer_id, [completed_by])

    # Anti-doublon : verifier qu'aucune completion active n'existe dans la fenetre
    # derivee de la recurrence (24h pour hebdo+, 1h sinon).
    gap_seconds = min_completion_gap_seconds(task.recurrence_rule, task.inferred_interval_days)
    window_start = datetime.now(timezone.utc) - timedelta(seconds=gap_seconds)
    recent = await session.execute(
        select(TaskCompletion)
        .where(
            TaskCompletion.task_id == task_id,
            TaskCompletion.completed_by == completed_by,
            TaskCompletion.deleted_at.is_(None),
            TaskCompletion.completed_at >= window_start,
        )
        .order_by(desc(TaskCompletion.completed_at))
        .limit(1)
    )
    last = recent.scalar_one_or_none()
    if last is not None:
        elapsed = datetime.now(timezone.utc) - last.completed_at
        retry_at = last.completed_at + timedelta(seconds=gap_seconds)
        elapsed_min = int(elapsed.total_seconds() // 60)
        # Anti-doublon : 409 localise. Anciennement detail={"message": ...,
        # "retry_at": ...} dict — converti en string localise via le handler
        # global. Le client n'utilisait pas la structure pour autre chose
        # qu'un toast.
        raise LocalizedHTTPException(
            status_code=409,
            error_code="COMPLETION_DUPLICATE",
            format_args={
                "elapsed_min": elapsed_min,
                "retry_at": retry_at.isoformat(),
            },
        )

    # Base points : moyenne des efforts ou weight, 0 pour tache perso.
    base_points = await compute_base_points_for_task(session, task)

    # Bonus/malus : multiplicateur selon l'etat de la tache a completed_at.
    completed_at_effective = body.completed_at or datetime.now(timezone.utc)
    mult_result = await compute_bonus_malus_for_completion(
        session, task, current_foyer_uuid(current), completed_at_effective,
    )
    # Tache perso (0 pt par design) : reason=PERSO, pas de bonus/malus.
    final_reason, final_mult = resolve_stored_reason_and_mult(base_points, mult_result)
    points = apply_multiplier_to_points(base_points, mult_result)

    completion = TaskCompletion(
        task_id=task_id,
        completed_by=completed_by,
        points_earned=points,
        multiplier=final_mult,
        reason=final_reason,
        notes=body.notes,
        client_id=_safe_uuid(current.get("device_id")),
    )
    if body.completed_at:
        completion.completed_at = body.completed_at
    session.add(completion)
    await session.flush()

    # Auto-desactiver les taches ponctuelles (sans recurrence)
    if task.recurrence_rule is None:
        task.is_active = False
        task.version += 1

    # Uncertain : recalculer inferred_interval_days a partir de l'historique
    # complet (la completion qu'on vient de creer est deja flush).
    await _after_completion(session, task, current_foyer_uuid(current), current["user_id"], completed_at_effective)

    await record_change(
        session, "task_completions", completion.id, "INSERT", current["user_id"],
        foyer_id=current["foyer_id"],
    )
    await session.commit()
    await session.refresh(completion)

    # Envoyer notification push (bucket "solo" — un seul participant).
    from app.services.push import send_completion_event
    await send_completion_event(
        session,
        completer_id=UUID(current["user_id"]),
        participant_ids=[UUID(current["user_id"])],
        task=task,
        foyer_id=current_foyer_uuid(current),
    )

    # « Souvent en retard » check, never breaks the completion.
    from app.services.prompts import bump_after_completion
    await bump_after_completion(session, task, current_foyer_uuid(current), [completion.reason])

    return completion


@router.post(
    "/api/tasks/{task_id}/complete-collective",
    response_model=CollectiveCompletionResponse,
    status_code=201,
)
async def complete_task_collective(
    task_id: UUID,
    body: CollectiveCompletionCreate,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Marque une tache comme completee par N participants (fait ensemble).

    Cree N lignes task_completions partageant le meme collective_id. Points
    divises en ceil integer. Check anti-doublon par participant : si AU MOINS
    un est en conflit, la totalite est abort (atomique) via HTTP 409.
    """
    # 1. Task existe et scope foyer
    result = await session.execute(
        visible_tasks(current).where(Task.id == task_id, Task.deleted_at.is_(None))
    )
    task = result.scalar_one_or_none()
    if not task:
        raise LocalizedHTTPException(status_code=404, error_code="TASK_NOT_FOUND")

    # 2. Validation : min 1 participant, tous distincts, tous membres du foyer
    participants = list(dict.fromkeys(body.participant_user_ids))  # dedup en preservant ordre
    if not participants:
        raise LocalizedHTTPException(status_code=400, error_code="COMPLETION_NO_PARTICIPANTS")

    foyer_uuid = current_foyer_uuid(current)
    members_result = await session.execute(
        select(UserFoyer.user_id).where(
            UserFoyer.foyer_id == foyer_uuid,
            UserFoyer.left_at.is_(None),
            UserFoyer.user_id.in_(participants),
        )
    )
    valid_ids = {row[0] for row in members_result.all()}
    invalid = [p for p in participants if p not in valid_ids]
    if invalid:
        raise await outsider_refusal(session, set(invalid), "COMPLETION_PARTICIPANTS_OUTSIDE_FOYER")

    # 3. Anti-doublon atomique : pour chaque participant, verifier qu'aucune
    # completion active n'existe dans la fenetre derivee de la recurrence.
    gap_seconds = min_completion_gap_seconds(task.recurrence_rule, task.inferred_interval_days)
    window_start = datetime.now(timezone.utc) - timedelta(seconds=gap_seconds)
    conflict_result = await session.execute(
        select(TaskCompletion)
        .where(
            TaskCompletion.task_id == task_id,
            TaskCompletion.completed_by.in_(participants),
            TaskCompletion.deleted_at.is_(None),
            TaskCompletion.completed_at >= window_start,
        )
    )
    conflicts = conflict_result.scalars().all()
    if conflicts:
        # Anti-doublon collectif : 409 localise. L'ancien detail etait un
        # dict {message, conflicts, gap_seconds} mais le client n'utilisait
        # que le message — converti en string localise via le handler.
        raise LocalizedHTTPException(
            status_code=409,
            error_code="COMPLETION_COLLECTIVE_DUPLICATE",
        )

    # 4. Base points : meme logique que le endpoint classique.
    base_points = await compute_base_points_for_task(session, task)

    # 5. Bonus/malus : multiplicateur applique SUR base_points AVANT le split.
    # Les N lignes partagent le meme multiplicateur et la meme reason (le
    # multiplicateur est une propriete de l'evenement collectif, pas des
    # participants individuels — cf design review).
    collective_id = uuid_lib.uuid4()
    completed_at = body.completed_at or datetime.now(timezone.utc)
    device_id = _safe_uuid(current.get("device_id"))

    mult_result = await compute_bonus_malus_for_completion(
        session, task, foyer_uuid, completed_at,
    )
    final_reason, final_mult = resolve_stored_reason_and_mult(base_points, mult_result)
    effective_points = apply_multiplier_to_points(base_points, mult_result)

    # Split ceil integer entre les participants. Si base=0 (perso), 0 chacun.
    points_per_user = math.ceil(effective_points / len(participants)) if effective_points else 0

    # 6. Creation des N lignes avec un meme collective_id.
    created: list[TaskCompletion] = []
    for user_id in participants:
        comp = TaskCompletion(
            task_id=task_id,
            completed_by=user_id,
            points_earned=points_per_user,
            multiplier=final_mult,
            reason=final_reason,
            notes=body.notes,
            client_id=device_id,
            collective_id=collective_id,
        )
        comp.completed_at = completed_at
        session.add(comp)
        created.append(comp)
    await session.flush()

    # 7. (legacy task_in_progress cleanup retire en v0.31, table droppee).

    # 8. Auto-desactiver les taches ponctuelles (une seule fois).
    if task.recurrence_rule is None:
        task.is_active = False
        task.version += 1

    # 8bis. Uncertain : recalculer inferred_interval_days sur tout l'historique.
    await _after_completion(session, task, foyer_uuid, current["user_id"], completed_at)

    # 9. Changelog : une entree par ligne pour que la sync offline rapatrie tout.
    for comp in created:
        await record_change(
            session, "task_completions", comp.id, "INSERT", current["user_id"],
            foyer_id=current["foyer_id"],
        )
    await session.commit()
    for comp in created:
        await session.refresh(comp)

    # 10. Notification push (bucket "with_me" / "without_me" selon destinataire).
    from app.services.push import send_completion_event
    await send_completion_event(
        session,
        completer_id=UUID(current["user_id"]),
        participant_ids=list(participants),
        task=task,
        foyer_id=foyer_uuid,
    )

    # 11. « Souvent en retard » check, never breaks the completion.
    from app.services.prompts import bump_after_completion
    await bump_after_completion(session, task, foyer_uuid, [c.reason for c in created])

    return CollectiveCompletionResponse(
        collective_id=collective_id,
        completions=[CompletionResponse.model_validate(c) for c in created],
    )


@router.delete("/api/tasks/{task_id}/completions/{completion_id}")
async def undo_completion(
    task_id: UUID,
    completion_id: UUID,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Annule une completion (soft delete). Scope foyer via JOIN tasks."""
    # Verifie que la completion existe ET appartient au foyer courant via Task
    result = await session.execute(
        select(TaskCompletion)
        .join(Task, Task.id == TaskCompletion.task_id)
        .where(
            TaskCompletion.id == completion_id,
            TaskCompletion.task_id == task_id,
            TaskCompletion.deleted_at.is_(None),
            Task.foyer_id == current_foyer_uuid(current),
        )
    )
    comp = result.scalar_one_or_none()
    if not comp:
        raise LocalizedHTTPException(status_code=404, error_code="COMPLETION_NOT_FOUND")
    comp.deleted_at = datetime.now(timezone.utc)
    await recompute_interval_of_task(session, comp.task_id)
    await record_change(
        session, "task_completions", comp.id, "DELETE", current["user_id"],
        foyer_id=current["foyer_id"],
    )
    await session.commit()
    return {"ok": True}


@router.delete(
    "/api/tasks/{task_id}/completions/collective/{collective_id}"
)
async def undo_collective_completion(
    task_id: UUID,
    collective_id: UUID,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Annule un evenement collectif entier (soft delete des N lignes).

    Le grouping cote UI traite un collectif comme un seul evenement ; sa
    suppression doit donc etre atomique. Une entree change_log par row pour
    que la sync offline rapatrie la suppression complete.
    """
    foyer_uuid = current_foyer_uuid(current)
    result = await session.execute(
        select(TaskCompletion)
        .join(Task, Task.id == TaskCompletion.task_id)
        .where(
            TaskCompletion.collective_id == collective_id,
            TaskCompletion.task_id == task_id,
            TaskCompletion.deleted_at.is_(None),
            Task.foyer_id == foyer_uuid,
        )
    )
    rows = list(result.scalars().all())
    if not rows:
        raise LocalizedHTTPException(status_code=404, error_code="COLLECTIVE_NOT_FOUND")

    now = datetime.now(timezone.utc)
    for comp in rows:
        comp.deleted_at = now
        await record_change(
            session, "task_completions", comp.id, "DELETE", current["user_id"],
            foyer_id=current["foyer_id"],
        )

    # Recalcul de inferred_interval_days vu qu'on retire des points d'ancrage.
    task_result = await session.execute(
        visible_tasks(current).where(Task.id == task_id, Task.deleted_at.is_(None))
    )
    task = task_result.scalar_one_or_none()
    if task is not None:
        await _recompute_inferred_interval(session, task, foyer_uuid)

    await session.commit()
    return {"ok": True, "deleted": len(rows)}


@router.get("/api/completions", response_model=list[CompletionResponse])
async def list_completions(
    user_id: UUID | None = None,
    task_id: UUID | None = None,
    # The app asks up to 2000 (refreshCompletions after a household switch).
    limit: int = Query(default=50, ge=1, le=5000),
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Liste les completions recentes avec filtres optionnels. Scope foyer via JOIN tasks."""
    q = (
        select(TaskCompletion)
        .join(Task, Task.id == TaskCompletion.task_id)
        .where(
            TaskCompletion.deleted_at.is_(None),
            Task.foyer_id == current_foyer_uuid(current),
            visible_to(Task.id, current),
        )
    )
    if user_id:
        q = q.where(TaskCompletion.completed_by == user_id)
    if task_id:
        q = q.where(TaskCompletion.task_id == task_id)
    q = q.order_by(desc(TaskCompletion.completed_at)).limit(limit)
    result = await session.execute(q)
    return result.scalars().all()


@router.get(
    "/api/tasks/{task_id}/completion-preview",
    response_model=CompletionPreview,
)
async def completion_preview(
    task_id: UUID,
    completed_at: datetime | None = None,
    num_participants: int = 1,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Preview du calcul bonus/malus pour une completion a venir.

    Appele par le dialog de completion cote Android pour afficher les points
    gagnes + la raison avant validation. Parametres :

    - `completed_at` (optionnel) : date de la completion. Defaut = maintenant.
      Permet le preview d'un backdate.
    - `num_participants` : 1 pour solo, N pour 'fait ensemble' (pour afficher
      les points par user apres split).

    Backend authoritative : meme formule qu'au POST /complete — pas de drift.
    """
    foyer_uuid = current_foyer_uuid(current)
    result = await session.execute(
        visible_tasks(current).where(Task.id == task_id, Task.deleted_at.is_(None))
    )
    task = result.scalar_one_or_none()
    if not task:
        raise LocalizedHTTPException(status_code=404, error_code="TASK_NOT_FOUND")

    ca = completed_at or datetime.now(timezone.utc)
    if ca.tzinfo is None:
        ca = ca.replace(tzinfo=timezone.utc)

    base_points = await compute_base_points_for_task(session, task)
    mult_result = await compute_bonus_malus_for_completion(session, task, foyer_uuid, ca)

    reason, multiplier = resolve_stored_reason_and_mult(base_points, mult_result)
    effective = apply_multiplier_to_points(base_points, mult_result)
    # Split preview pour collective (informatif seulement, le back le refait
    # au POST). Cap num_participants >= 1 pour eviter div/0.
    n = max(1, num_participants)
    final_per_user = math.ceil(effective / n) if effective > 0 else 0

    return CompletionPreview(
        base_points=base_points,
        multiplier=multiplier,
        final_points=final_per_user,
        reason=reason.value,
        days_early=mult_result.days_early,
        days_late=mult_result.days_late,
        pct_cycle=round(mult_result.pct_cycle, 4),
    )
