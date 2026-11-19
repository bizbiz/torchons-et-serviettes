"""Routes prompts — queue unifiee de questions a poser aux users.

GET  /api/prompts/next         : prochain prompt non-resolu (1 ou 204)
POST /api/prompts/{id}/shown   : marque le prompt comme affiche
POST /api/prompts/{id}/resolve : resout le prompt (DISMISSED / ANSWERED)
"""

from __future__ import annotations

import uuid as uuid_lib
from datetime import date, datetime, timedelta, timezone
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Response
from pydantic import BaseModel
from sqlalchemy import and_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.models.context import Context, TaskContext
from app.models.enums import PromptResolution, PromptType
from app.models.presence_day import PresenceDay
from app.models.task import Task
from app.utils.errors import LocalizedHTTPException
from app.services.recurrence import is_votable
from app.models.task_effort import TaskEffort
from app.models.user_prompt import UserPrompt
from app.services.prompt_validity import close_if_stale
from app.models.foyer import Foyer
from app.models.module_state import ModuleState
from app.services.modules import (
    PROMPT_TYPE_MODULE,
    get_registry,
    resolve_states,
    unmet_requirements,
)
from app.services.revote_votes import revote_answer_args
from app.utils.scoping import current_foyer_uuid

router = APIRouter(prefix="/api", tags=["prompts"])


# --- Schemas ---

class PromptResponse(BaseModel):
    id: UUID
    type: str
    priority: int
    context: dict
    created_at: datetime
    shown_at: datetime | None

    model_config = {"from_attributes": True}

    @classmethod
    def from_orm_prompt(cls, p: UserPrompt) -> "PromptResponse":
        return cls(
            id=p.id,
            type=p.type.value,
            priority=p.priority,
            context=p.context,
            created_at=p.created_at,
            shown_at=p.shown_at,
        )


class PromptResolveRequest(BaseModel):
    resolution: str  # "DISMISSED" ou "ANSWERED"
    payload: dict | None = None


class RequestRevoteBody(BaseModel):
    """Demande explicite a un autre user de (re)voter sur la frequence ou
    l'effort d'une tache. Le requester (user courant) doit etre membre du
    meme foyer que le target_user_id et la tache doit etre dans ce foyer."""
    target_user_id: UUID
    task_id: UUID
    kind: str   # "frequency" | "effort"


# --- Endpoints ---

@router.get("/prompts/next")
async def get_next_prompt(
    response: Response,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Retourne le prochain prompt non-resolu (le plus prioritaire, le plus ancien).

    204 No Content si rien. Le front doit appeler POST /shown des l'affichage.
    """
    user_id = UUID(current["user_id"])
    foyer_uuid = current_foyer_uuid(current)
    now = datetime.now(timezone.utc)

    # Filtre module : un prompt dont le module a ete eteint ne doit plus etre
    # servi. Les generateurs ont deja leur garde, mais elle ne dit rien de la
    # file DEJA constituee au moment ou l'on coupe — et c'est justement ce
    # stock qui produirait la pire experience : couper une fonctionnalite puis
    # continuer a etre interroge par elle.
    states = await resolve_states(session, foyer_uuid, user_id)
    muted = [
        prompt_type
        for prompt_type, module_key in PROMPT_TYPE_MODULE.items()
        if not states.get(module_key, False)
    ]
    # DISCOVER_MODULE (P4) suit la regle INVERSE : on ne propose que ce qui est
    # eteint. Un module active entre la creation du prompt et sa lecture — par
    # l'autre membre, ou depuis Reglages — rend la proposition caduque, et
    # proposer d'activer ce qui l'est deja est le ridicule le plus previsible
    # du mecanisme.
    already_on = [
        key for key, enabled in states.items() if enabled
    ]

    conditions = [
        UserPrompt.user_id == user_id,
        UserPrompt.foyer_id == foyer_uuid,
        UserPrompt.resolved_at.is_(None),
        # Exclure les expires
        (UserPrompt.expires_at.is_(None)) | (UserPrompt.expires_at > now),
    ]
    if muted:
        conditions.append(UserPrompt.type.notin_(muted))
    if already_on:
        conditions.append(
            (UserPrompt.type != PromptType.DISCOVER_MODULE)
            | (UserPrompt.context["module_key"].astext.notin_(already_on))
        )

    # On prend plusieurs candidats et non le premier : servir le prompt en tete
    # sans verifier que sa condition tient encore, c'est exactement le defaut
    # corrige le 2026-08-23 — une pop-up qui reclamait des jours deja remplis.
    candidats = (await session.execute(
        select(UserPrompt)
        .where(*conditions)
        .order_by(UserPrompt.priority.desc(), UserPrompt.created_at.asc())
        .limit(10)
    )).scalars().all()

    prompt = None
    referme = False
    for candidat in candidats:
        if await close_if_stale(session, candidat):
            referme = True
            continue
        prompt = candidat
        break
    if referme:
        await session.commit()

    if prompt is None:
        response.status_code = 204
        return None

    return PromptResponse.from_orm_prompt(prompt)


@router.post("/prompts/{prompt_id}/shown")
async def mark_shown(
    prompt_id: UUID,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Marque le prompt comme affiche (shown_at). Idempotent."""
    user_id = UUID(current["user_id"])
    result = await session.execute(
        select(UserPrompt).where(
            UserPrompt.id == prompt_id,
            UserPrompt.user_id == user_id,
        )
    )
    prompt = result.scalar_one_or_none()
    if prompt is None:
        raise LocalizedHTTPException(status_code=404, error_code="PROMPT_NOT_FOUND")

    if prompt.shown_at is None:
        prompt.shown_at = datetime.now(timezone.utc)
        await session.commit()

    return {"ok": True}


@router.post("/prompts/{prompt_id}/resolve")
async def resolve_prompt(
    prompt_id: UUID,
    body: PromptResolveRequest,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Resout un prompt. Actions selon type + resolution.

    BUMP_EFFORT + ANSWERED + payload.action=="bump_effort" : incremente le
    vote effort de l'user de +1 pour la tache.

    VOTE_RATING + ANSWERED : le front a deja soumis via les endpoints
    existants (set_affinity, set_effort). On marque juste comme resolu.
    """
    user_id = UUID(current["user_id"])
    result = await session.execute(
        select(UserPrompt).where(
            UserPrompt.id == prompt_id,
            UserPrompt.user_id == user_id,
        )
    )
    prompt = result.scalar_one_or_none()
    if prompt is None:
        raise LocalizedHTTPException(status_code=404, error_code="PROMPT_NOT_FOUND")

    if prompt.resolved_at is not None:
        return {"ok": True, "already_resolved": True}

    # Resolution
    resolution = body.resolution.upper()
    if resolution not in ("DISMISSED", "ANSWERED"):
        raise LocalizedHTTPException(status_code=400, error_code="PROMPT_INVALID_RESOLUTION")

    prompt.resolved_at = datetime.now(timezone.utc)
    prompt.resolution = PromptResolution(resolution)

    # Actions specifiques
    payload = body.payload or {}
    foyer_uuid = current_foyer_uuid(current)

    if prompt.type == PromptType.BUMP_EFFORT and resolution == "ANSWERED":
        action = payload.get("action", "")
        if action == "bump_effort":
            task_id = prompt.context.get("task_id")
            if task_id:
                # Incrementer le vote effort de l'user de +1
                effort_result = await session.execute(
                    select(TaskEffort).where(
                        TaskEffort.task_id == uuid_lib.UUID(task_id),
                        TaskEffort.user_id == user_id,
                    )
                )
                effort = effort_result.scalar_one_or_none()
                if effort:
                    effort.points = min(18, effort.points + 1)
                else:
                    # Pas de vote existant — en creer un a 4 (defaut 3 + 1)
                    new_effort = TaskEffort(
                        task_id=uuid_lib.UUID(task_id),
                        user_id=user_id,
                        points=4,
                    )
                    session.add(new_effort)

    elif prompt.type == PromptType.BUMP_EFFORT and payload.get("action") == "later":
        # « Plus tard »: asked again on the next late completion, without the
        # grace period (services/prompts.py, _members_to_ask). « Ignorer » and
        # an older client send DISMISSED without it and keep the grace.
        prompt.context = {**(prompt.context or {}), "postponed": True}

    elif prompt.type == PromptType.RESOLVE_PRESENCE and resolution == "ANSWERED":
        await _handle_resolve_presence(session, foyer_uuid, user_id, payload)

    elif prompt.type == PromptType.DISCOVER_MODULE and resolution == "DISMISSED":
        # Un refus ne compte dans le budget que s'il est EXPLICITE, c'est-a-dire
        # si le client a affiche la proposition et que quelqu'un a dit non. Un
        # client qui ignore ce type de prompt le resout en DISMISSED sans rien
        # montrer : le compter brulerait un des deux refus d'un module que
        # personne n'a vu proposer.
        if payload.get("declined"):
            ctx = dict(prompt.context or {})
            ctx["declined"] = True
            prompt.context = ctx

    elif prompt.type == PromptType.DISCOVER_MODULE and resolution == "ANSWERED":
        await _accept_module_proposal(session, prompt, foyer_uuid, user_id)

    # A re-vote answered or closed: the requester hears of it (read before the commit, sent after).
    revote = await revote_answer_args(session, prompt, user_id, resolution, payload)
    await session.commit()
    if revote:
        from app.services.push_revote import send_revote_answer

        await send_revote_answer(session, **revote)
    return {"ok": True}


async def _accept_module_proposal(session, prompt, foyer_uuid, user_id) -> None:
    """DISCOVER_MODULE answered: switch the proposed module on."""
    # Accepter une proposition ACTIVE le module, ici et non par un second
    # appel du client : deux allers-retours pour un seul geste, c'est un
    # etat intermediaire ou le prompt est resolu et le module toujours
    # eteint, avec un reseau qui peut tomber entre les deux.
    module_key = prompt.context.get("module_key")
    module = get_registry().by_key(module_key) if module_key else None
    if module is not None:
        states = await resolve_states(session, foyer_uuid, user_id)
        # Les dependances restent souveraines : une proposition ne doit
        # pas etre un chemin detourne pour allumer ce que PUT /modules
        # refuserait.
        if not unmet_requirements(module, states):
            row_user_id = None if module.is_collective else user_id
            existing = await session.execute(
                select(ModuleState).where(
                    ModuleState.foyer_id == foyer_uuid,
                    ModuleState.module_key == module.key,
                    ModuleState.user_id.is_(None)
                    if row_user_id is None
                    else ModuleState.user_id == row_user_id,
                )
            )
            state = existing.scalar_one_or_none()
            now = datetime.now(timezone.utc)
            if state is None:
                session.add(
                    ModuleState(
                        foyer_id=foyer_uuid,
                        user_id=row_user_id,
                        module_key=module.key,
                        enabled=True,
                        enabled_at=now,
                        updated_by_user_id=user_id,
                    )
                )
            else:
                state.enabled = True
                state.enabled_at = now
                state.updated_by_user_id = user_id
            # Miroir legacy, tant que l'APK diffuse lit encore la colonne.
            if module.key == "motivation":
                foyer = await session.get(Foyer, foyer_uuid)
                if foyer is not None:
                    foyer.gamification_enabled = True


async def _validate_revote(session, body, foyer_uuid, requester_id):
    """Checks of request_revote; returns (task, prompt_type) or raises."""
    from app.models.user_foyer import UserFoyer

    # 1. Validation : tache existe + appartient au foyer.
    task_r = await session.execute(
        select(Task).where(Task.id == body.task_id, Task.foyer_id == foyer_uuid)
    )
    task = task_r.scalar_one_or_none()
    if task is None:
        raise LocalizedHTTPException(status_code=404, error_code="TASK_NOT_FOUND")

    # 2. Validation : target user appartient au foyer.
    member_r = await session.execute(
        select(UserFoyer).where(
            UserFoyer.foyer_id == foyer_uuid,
            UserFoyer.left_at.is_(None),
            UserFoyer.user_id == body.target_user_id,
        )
    )
    if member_r.scalar_one_or_none() is None:
        raise LocalizedHTTPException(status_code=404, error_code="FOYER_MEMBER_NOT_FOUND")

    # 3. Pas de demande a soi-meme (UI ne devrait pas le proposer mais on protege).
    if body.target_user_id == requester_id:
        raise LocalizedHTTPException(status_code=400, error_code="PROMPT_OWN_VOTE")

    # 4. Mapping kind -> PromptType.
    if body.kind == "frequency":
        if not is_votable(task.recurrence_rule):
            raise LocalizedHTTPException(status_code=409, error_code="GAMIF_RULE_NOT_VOTABLE")
        return task, PromptType.VOTE_FREQUENCY
    if body.kind == "effort":
        return task, PromptType.VOTE_EFFORT
    raise LocalizedHTTPException(status_code=400, error_code="PROMPT_INVALID_KIND")


@router.post("/prompts/request-revote")
async def request_revote(
    body: RequestRevoteBody,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Enqueue un VOTE_FREQUENCY / VOTE_EFFORT prompt pour `target_user_id`
    sur `task_id`.

    Idempotent : si un prompt actif (non resolved, non expired) du meme type
    existe deja pour ce couple (user, task), on ne re-enqueue pas, on
    retourne 409 avec l'existant. UI cote client (J1 du grilling) doit
    desactiver le bouton "Demander revote" quand pending — le 409 est
    une defense en profondeur.

    Priority 6, expires None (decision M3 + N2 du grilling).
    """
    from uuid import UUID as _UUID
    from app.models.task import Task
    from app.models.user_foyer import UserFoyer
    from app.models.user import User

    foyer_uuid = current_foyer_uuid(current)
    requester_id = _UUID(current["user_id"])

    task, prompt_type = await _validate_revote(session, body, foyer_uuid, requester_id)

    # 5. Idempotence : prompt actif du meme type (user, task) existant ?
    now = datetime.now(timezone.utc)
    existing_r = await session.execute(
        select(UserPrompt).where(
            UserPrompt.user_id == body.target_user_id,
            UserPrompt.type == prompt_type,
            UserPrompt.resolved_at.is_(None),
            (UserPrompt.expires_at.is_(None)) | (UserPrompt.expires_at > now),
        )
    )
    for p in existing_r.scalars().all():
        if p.context.get("task_id") == str(body.task_id):
            raise HTTPException(
                status_code=409,
                detail={
                    "code": "PROMPT_ALREADY_PENDING",
                    "prompt_id": str(p.id),
                    "created_at": p.created_at.isoformat(),
                },
            )

    # 6. Snapshot du contexte fige : nom du requester + valeur courante.
    requester_r = await session.execute(
        select(User.display_name).where(User.id == requester_id)
    )
    requester_name = requester_r.scalar() or "?"

    current_value: dict = {}
    if prompt_type == PromptType.VOTE_FREQUENCY:
        current_value = {"recurrence_rule": task.recurrence_rule}
    from app.services.revote_votes import SNAPSHOT_KEY, current_vote

    # The target's own vote now (None if none): the answer is compared with it.
    current_value[SNAPSHOT_KEY] = await current_vote(session, body.task_id, body.target_user_id, body.kind)

    context = {
        "task_id": str(body.task_id),
        "task_title": task.title,
        "requester_user_id": str(requester_id),
        "requester_name": requester_name,
        "kind": body.kind,
        **current_value,
    }

    new_prompt = UserPrompt(
        user_id=body.target_user_id,
        foyer_id=foyer_uuid,
        type=prompt_type,
        priority=6,             # M3 du grilling : un cran au-dessus de VOTE_RATING
        context=context,
        expires_at=None,        # N2 du grilling : pas d'expiration
    )
    session.add(new_prompt)
    await session.flush()
    await session.commit()

    # After the commit: the target is notified at once (services/push.py).
    from app.services.push_revote import send_revote_request

    notified = await send_revote_request(
        session, requester_id, body.target_user_id, body.task_id, task.title, body.kind,
    )

    return {
        "ok": True,
        "notified": notified,
        "prompt_id": str(new_prompt.id),
        "type": prompt_type.value,
    }


async def _handle_resolve_presence(
    session: AsyncSession,
    foyer_id: UUID,
    resolved_by: UUID,
    payload: dict,
) -> None:
    """Traite la resolution d'un prompt RESOLVE_PRESENCE.

    Deux formats de payload :
    - Mode calendrier : {"days": {"2026-04-14": "present", "2026-04-15": "absent", ...}}
    - Mode simplifie : {"absent_since": "2026-03-01"} (tout absent depuis cette date)

    Insere les lignes dans presence_days et auto-resout les prompts
    RESOLVE_PRESENCE des autres users du foyer.
    """
    if "days" in payload:
        # Mode calendrier (<=30 jours)
        days_map: dict[str, str] = payload["days"]
        for day_str, status_str in days_map.items():
            day = date.fromisoformat(day_str)
            status = "confirmed_present" if status_str == "present" else "confirmed_absent"
            await _upsert_presence_day(session, foyer_id, day, status, "manual")

    elif "absent_since" in payload:
        # Mode simplifie (>30 jours)
        absent_since = date.fromisoformat(payload["absent_since"])
        today = datetime.now(timezone.utc).date()
        # One upsert per day: an arbitrary past date was an unbounded loop. Absences
        # older than a year no longer weigh on any interval, so they are not written.
        cursor = max(absent_since, today - timedelta(days=366))
        while cursor < today:
            await _upsert_presence_day(
                session, foyer_id, cursor, "confirmed_absent", "manual"
            )
            cursor += timedelta(days=1)

    await session.flush()

    # Invalidation : les tâches `requires_home` du foyer ont peut-etre leur
    # `inferred_interval_days` obsolete (uncertain) et leur urgence affichee
    # cote client doit etre re-calculee. On bump task.version pour signaler le
    # changement (le client refait un GET /api/tasks au prochain refresh et
    # recupere `absent_days_since_last_completion` recalcule a la volee).
    # On recalcule aussi `inferred_interval_days` pour les `uncertain`.
    await _recompute_after_presence_change(session, foyer_id)

    # Auto-resoudre les prompts RESOLVE_PRESENCE des autres users du foyer
    other_prompts_result = await session.execute(
        select(UserPrompt).where(
            UserPrompt.foyer_id == foyer_id,
            UserPrompt.type == PromptType.RESOLVE_PRESENCE,
            UserPrompt.resolved_at.is_(None),
            UserPrompt.user_id != resolved_by,
        )
    )
    for other_prompt in other_prompts_result.scalars().all():
        other_prompt.resolved_at = datetime.now(timezone.utc)
        other_prompt.resolution = PromptResolution.ANSWERED


async def _recompute_after_presence_change(
    session: AsyncSession,
    foyer_id: UUID,
) -> None:
    """Apres modification des `presence_days`, recalcule l'etat des taches
    `requires_home` du foyer :

    1. Bump `task.version` sur toutes les taches `requires_home` (signal pour
       le client qu'un refresh est utile).
    2. Recalcule `inferred_interval_days` pour les taches `uncertain` —
       reutilise `_recompute_inferred_interval` (completions.py).

    Idempotent : safe a appeler plusieurs fois pour le meme foyer.
    """
    # Charger toutes les taches `requires_home` actives du foyer
    result = await session.execute(
        select(Task)
        .join(TaskContext, TaskContext.task_id == Task.id)
        .join(Context, Context.id == TaskContext.context_id)
        .where(
            Task.foyer_id == foyer_id,
            Task.deleted_at.is_(None),
            Context.requires_presence.is_(True),
            Context.accrues_when_away.is_(False),
            Context.deleted_at.is_(None),
        )
    )
    tasks = result.scalars().all()
    if not tasks:
        return

    # Import tardif pour eviter une boucle d'import (completions.py importe deja
    # services qui peuvent eux-memes importer schemas/api).
    from app.api.completions import _recompute_inferred_interval

    for task in tasks:
        task.version += 1
        await _recompute_inferred_interval(session, task, foyer_id)
    await session.flush()


async def _upsert_presence_day(
    session: AsyncSession,
    foyer_id: UUID,
    day: date,
    status: str,
    source: str,
) -> None:
    """Insere ou met a jour une ligne presence_days."""
    existing = await session.execute(
        select(PresenceDay).where(
            PresenceDay.foyer_id == foyer_id,
            PresenceDay.date == day,
        )
    )
    row = existing.scalar_one_or_none()
    if row is None:
        session.add(PresenceDay(
            foyer_id=foyer_id,
            date=day,
            status=status,
            source=source,
        ))
    else:
        # Ne pas ecraser un confirmed_present auto_wifi par un manual absent
        # (le signal WiFi positif est fiable)
        if not (row.status == "confirmed_present" and row.source == "auto_wifi"
                and status == "confirmed_absent"):
            row.status = status
            row.source = source
