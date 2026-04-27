"""Routes completions — marquer une tache comme faite."""

from __future__ import annotations

import math
import uuid as uuid_lib
from datetime import datetime, timedelta, timezone
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select, desc
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.models.category import Category
from app.models.completion import TaskCompletion
from app.models.fair_share import FairShareConfig
from app.models.foyer import Foyer
from app.models.presence_day import PresenceDay
from app.models.task import Task
from app.models.task_in_progress import TaskInProgress
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
from app.utils.changelog import record_change
from app.utils.scoping import current_foyer_uuid, scoped

router = APIRouter(tags=["completions"])


def _safe_uuid(value: str | None) -> UUID | None:
    """Convertit en UUID si possible, sinon retourne None."""
    if not value:
        return None
    try:
        return UUID(value)
    except (ValueError, AttributeError):
        return None


async def _recompute_inferred_interval(
    session: AsyncSession,
    task: Task,
    foyer_id: UUID,
) -> None:
    """Recalcule task.inferred_interval_days apres une completion (v0.14).

    Ne fait rien si la tache n'est pas de type 'uncertain'. Sinon :
    1. Charge toutes les completions de la tache (chronologique).
    2. Si la tache est requires_home (via sa categorie) ET le foyer a
       home_freeze_enabled : calcule les jours absents pour chaque paire
       consecutive (Q6.2, batch via une seule query presence_days).
    3. Appelle compute_inferred_interval_days et persiste le resultat.

    Le caller doit avoir flush la nouvelle completion AVANT d'appeler cette
    fonction pour qu'elle soit visible dans la query.
    """
    if not is_uncertain(task.recurrence_rule):
        return

    comp_result = await session.execute(
        select(TaskCompletion.completed_at)
        .where(
            TaskCompletion.task_id == task.id,
            TaskCompletion.deleted_at.is_(None),
        )
        .order_by(TaskCompletion.completed_at.asc())
    )
    times = [row[0] for row in comp_result.all()]

    if len(times) < 2:
        task.inferred_interval_days = None
        task.version += 1
        return

    # Freeze check : soustrait les jours absents uniquement si la tache est
    # requires_home (via categorie) ET freeze actif au niveau foyer (Q6.2).
    absent_per_gap: list[int] | None = None
    requires_home = False
    if task.category_id is not None:
        cat_result = await session.execute(
            select(Category.requires_home).where(Category.id == task.category_id)
        )
        requires_home = bool(cat_result.scalar() or False)

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




@router.post("/api/tasks/{task_id}/complete", response_model=CompletionResponse, status_code=201)
async def complete_task(
    task_id: UUID,
    body: CompletionCreate = CompletionCreate(),
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Marque une tache comme completee (scope foyer)."""
    result = await session.execute(
        scoped(Task, current).where(Task.id == task_id, Task.deleted_at.is_(None))
    )
    task = result.scalar_one_or_none()
    if not task:
        raise HTTPException(status_code=404, detail="Tache introuvable")

    completed_by = body.completed_by or UUID(current["user_id"])

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
        raise HTTPException(
            status_code=409,
            detail={
                "message": (
                    f"Tache deja completee il y a {elapsed_min} min. "
                    f"Prochain creneau possible a {retry_at.isoformat()}."
                ),
                "last_completion_at": last.completed_at.isoformat(),
                "retry_at": retry_at.isoformat(),
                "gap_seconds": gap_seconds,
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

    # Supprimer la tache "en cours" si elle existe
    from sqlalchemy import delete
    await session.execute(
        delete(TaskInProgress).where(TaskInProgress.task_id == task_id)
    )

    # Auto-desactiver les taches ponctuelles (sans recurrence)
    if task.recurrence_rule is None:
        task.is_active = False
        task.version += 1

    # Uncertain : recalculer inferred_interval_days a partir de l'historique
    # complet (la completion qu'on vient de creer est deja flush).
    await _recompute_inferred_interval(session, task, current_foyer_uuid(current))

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
        task_title=task.title,
        foyer_id=current_foyer_uuid(current),
    )

    # Trigger analyse bump (fire-and-forget)
    try:
        from app.services.prompts import generate_bump_prompts
        await generate_bump_prompts(session, task, current_foyer_uuid(current))
        await session.commit()
    except Exception:
        pass  # ne jamais casser la completion

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
        scoped(Task, current).where(Task.id == task_id, Task.deleted_at.is_(None))
    )
    task = result.scalar_one_or_none()
    if not task:
        raise HTTPException(status_code=404, detail="Tache introuvable")

    # 2. Validation : min 1 participant, tous distincts, tous membres du foyer
    participants = list(dict.fromkeys(body.participant_user_ids))  # dedup en preservant ordre
    if not participants:
        raise HTTPException(status_code=400, detail="Aucun participant fourni")

    foyer_uuid = current_foyer_uuid(current)
    members_result = await session.execute(
        select(UserFoyer.user_id).where(
            UserFoyer.foyer_id == foyer_uuid,
            UserFoyer.user_id.in_(participants),
        )
    )
    valid_ids = {row[0] for row in members_result.all()}
    invalid = [p for p in participants if p not in valid_ids]
    if invalid:
        raise HTTPException(
            status_code=400,
            detail=f"Participants hors du foyer : {[str(i) for i in invalid]}",
        )

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
        now = datetime.now(timezone.utc)
        details = []
        for c in conflicts:
            elapsed_min = int((now - c.completed_at).total_seconds() // 60)
            retry_at = c.completed_at + timedelta(seconds=gap_seconds)
            details.append({
                "user_id": str(c.completed_by),
                "elapsed_min": elapsed_min,
                "retry_at": retry_at.isoformat(),
            })
        raise HTTPException(
            status_code=409,
            detail={
                "message": (
                    "Impossible de valider : un ou plusieurs participants ont "
                    "deja valide cette tache recemment."
                ),
                "conflicts": details,
                "gap_seconds": gap_seconds,
            },
        )

    # 4. Base points : meme logique que le endpoint classique.
    base_points = await compute_base_points_for_task(session, task)

    # 5. Bonus/malus : multiplicateur applique SUR base_points AVANT le split.
    # Les N lignes partagent le meme multiplicateur et la meme reason (le
    # multiplicateur est une propriete de l'evenement collectif, pas des
    # participants individuels — cf grill-me).
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

    # 7. Supprimer la tache "en cours" si elle existe (meme si la UI "en cours"
    # a ete retiree en v0.11, des entrees legacy peuvent subsister cote DB).
    from sqlalchemy import delete
    await session.execute(
        delete(TaskInProgress).where(TaskInProgress.task_id == task_id)
    )

    # 8. Auto-desactiver les taches ponctuelles (une seule fois).
    if task.recurrence_rule is None:
        task.is_active = False
        task.version += 1

    # 8bis. Uncertain : recalculer inferred_interval_days sur tout l'historique.
    await _recompute_inferred_interval(session, task, foyer_uuid)

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
        task_title=task.title,
        foyer_id=foyer_uuid,
    )

    # 11. Trigger analyse bump (fire-and-forget)
    try:
        from app.services.prompts import generate_bump_prompts
        await generate_bump_prompts(session, task, foyer_uuid)
        await session.commit()
    except Exception:
        pass

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
        raise HTTPException(status_code=404, detail="Completion introuvable")
    comp.deleted_at = datetime.now(timezone.utc)
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
        raise HTTPException(status_code=404, detail="Collectif introuvable")

    now = datetime.now(timezone.utc)
    for comp in rows:
        comp.deleted_at = now
        await record_change(
            session, "task_completions", comp.id, "DELETE", current["user_id"],
            foyer_id=current["foyer_id"],
        )

    # Recalcul de inferred_interval_days vu qu'on retire des points d'ancrage.
    task_result = await session.execute(
        scoped(Task, current).where(Task.id == task_id, Task.deleted_at.is_(None))
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
    limit: int = 50,
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
        scoped(Task, current).where(Task.id == task_id, Task.deleted_at.is_(None))
    )
    task = result.scalar_one_or_none()
    if not task:
        raise HTTPException(status_code=404, detail="Tache introuvable")

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
