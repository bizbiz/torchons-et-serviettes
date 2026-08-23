"""Routes presence — systeme hybride WiFi auto + resolution manuelle (v2).

Le client Android appelle POST /api/presence/checkin quand il detecte le WiFi
maison au lancement de l'app (signal positif uniquement). Le backend :
1. Insere un confirmed_present dans presence_days pour aujourd'hui.
2. Detecte les jours incertains (sans ligne) entre le dernier jour confirme
   et hier.
3. S'il y a des jours incertains, insere un prompt RESOLVE_PRESENCE (priority
   max) pour chaque user du foyer.

Note : le PresenceWorker en arriere-plan a ete supprime car les restrictions
Android recentes (Doze, background limits) empechent son execution fiable.
Le seul signal automatique est le check WiFi a l'ouverture de l'app.
"""

from __future__ import annotations

import uuid
from datetime import date, datetime, timedelta, timezone

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel
from sqlalchemy import and_, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.utils.errors import LocalizedHTTPException
from app.models.completion import TaskCompletion
from app.models.enums import PromptType, PromptResolution
from app.models.presence_day import PresenceDay
from app.models.task import Task
from app.models.user_foyer import UserFoyer
from app.models.user_prompt import UserPrompt
from app.services.module_discovery import propose_module
from app.services.presence import count_absent_days_since
from app.utils.scoping import current_foyer_uuid

#: Nombre de jours absents sur les 15 derniers qui declenche la proposition
#: du module Absences. Trois jours, c'est le seuil ou "on etait partis" cesse
#: d'etre une exception et commence a peser sur les taches.
ABSENCE_PROPOSAL_THRESHOLD_DAYS = 3

router = APIRouter(prefix="/api", tags=["presence"])

# Timezone de reference pour la granularite jour.
# Hardcode Europe/Paris — app couple FR, pas de multi-timezone.
try:
    from zoneinfo import ZoneInfo
    TZ_LOCAL = ZoneInfo("Europe/Paris")
except (ImportError, KeyError):
    from datetime import timezone as _tz
    # Fallback UTC+2 (CEST, heure d'ete Paris — acceptable pour un jour calendaire)
    TZ_LOCAL = _tz(timedelta(hours=2))

# Seuil au-dela duquel on passe en mode simplifie (pas de calendrier
# jour par jour, mais un ecran avec "absent depuis le X").
CALENDAR_MAX_DAYS = 30


def _today_local() -> date:
    """Date du jour en Europe/Paris."""
    return datetime.now(TZ_LOCAL).date()


# Plage maximale pour GET /presence — borne anti-abus (un foyer ne peut pas
# demander des annees de donnees d'un coup).
PRESENCE_RANGE_MAX_DAYS = 400


# --- Schemas ---


class PresenceDayOut(BaseModel):
    """Une ligne presence_days exposee au client (calendrier partage)."""

    date: date
    status: str  # confirmed_present / confirmed_absent
    source: str  # auto_wifi / manual / foyer_init

    model_config = {"from_attributes": True}


class PresenceDayPut(BaseModel):
    """Un jour a poser via PUT /presence.

    status :
    - "present" -> confirmed_present
    - "absent"  -> confirmed_absent
    - "clear"   -> supprime la ligne (le jour redevient incertain)
    """

    date: date
    status: str


@router.get("/presence", response_model=list[PresenceDayOut])
async def get_presence(
    from_date: date = Query(..., alias="from"),
    to_date: date = Query(..., alias="to"),
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
) -> list[PresenceDayOut]:
    """Jours de presence du foyer courant dans la plage [from, to] (inclus).

    Foyer-scope via get_current_user. Sert le calendrier partage (ecran Foyer
    + prompt RESOLVE_PRESENCE). Les jours sans ligne sont "incertains" et
    n'apparaissent simplement pas dans la reponse (le client les rend gris).
    """
    if from_date > to_date:
        raise LocalizedHTTPException(status_code=400, error_code="PRESENCE_FROM_GT_TO")
    if (to_date - from_date).days > PRESENCE_RANGE_MAX_DAYS:
        raise LocalizedHTTPException(
            status_code=400,
            error_code="PRESENCE_RANGE_TOO_LARGE_FORMAT",
            format_args={"max_days": PRESENCE_RANGE_MAX_DAYS},
        )

    foyer_uuid = current_foyer_uuid(current)
    result = await session.execute(
        select(PresenceDay)
        .where(
            PresenceDay.foyer_id == foyer_uuid,
            PresenceDay.date >= from_date,
            PresenceDay.date <= to_date,
        )
        .order_by(PresenceDay.date)
    )
    rows = result.scalars().all()
    return [PresenceDayOut.model_validate(r) for r in rows]


class ActivityDaysOut(BaseModel):
    """Jours d'activite d'un membre (couche etoile/streak du calendrier)."""

    days: list[date]


@router.get("/activity-days", response_model=ActivityDaysOut)
async def get_activity_days(
    user_id: uuid.UUID = Query(...),
    from_date: date = Query(..., alias="from"),
    to_date: date = Query(..., alias="to"),
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
) -> ActivityDaysOut:
    """Jours ou `user_id` a fait >=1 tache du foyer courant dans [from, to].

    Couche etoile/streak du calendrier partage (decision 5/6 du design) : la
    presence reste foyer-globale, seule cette couche depend du membre
    selectionne. Un jour "actif" = au moins une completion NON supprimee d'une
    tache appartenant au foyer courant, completed_by = user_id.

    Bucketage par `func.date(completed_at)` — identique aux autres aggregats
    de completions (stats/long-term, couple-totals) pour rester coherent. Le
    parametre `user_id` doit etre membre du foyer courant (sinon liste vide,
    pas de fuite cross-foyer). Plage bornee comme GET /presence (anti-abus).
    """
    if from_date > to_date:
        raise LocalizedHTTPException(status_code=400, error_code="PRESENCE_FROM_GT_TO")
    if (to_date - from_date).days > PRESENCE_RANGE_MAX_DAYS:
        raise LocalizedHTTPException(
            status_code=400,
            error_code="PRESENCE_RANGE_TOO_LARGE_FORMAT",
            format_args={"max_days": PRESENCE_RANGE_MAX_DAYS},
        )

    foyer_uuid = current_foyer_uuid(current)

    # Garde-fou isolation : user_id doit etre (ou avoir ete) membre du foyer
    # courant. On n'exclut PAS les former_member ici : l'historique d'activite
    # d'un ancien membre reste consultable dans le calendrier du foyer.
    member_check = await session.execute(
        select(UserFoyer.user_id).where(
            UserFoyer.foyer_id == foyer_uuid,
            UserFoyer.user_id == user_id,
        )
    )
    if member_check.scalar_one_or_none() is None:
        # Non-membre : pas de fuite, on renvoie une liste vide.
        return ActivityDaysOut(days=[])

    # Jours distincts avec >=1 completion non supprimee de ce membre sur une
    # tache du foyer courant (scope transitif via JOIN tasks).
    day_col = func.date(TaskCompletion.completed_at).label("d")
    result = await session.execute(
        select(day_col)
        .join(Task, Task.id == TaskCompletion.task_id)
        .where(
            TaskCompletion.completed_by == user_id,
            TaskCompletion.deleted_at.is_(None),
            Task.foyer_id == foyer_uuid,
            day_col >= from_date,
            day_col <= to_date,
        )
        .group_by("d")
        .order_by("d")
    )
    return ActivityDaysOut(days=[row[0] for row in result.all()])


@router.put("/presence", response_model=list[PresenceDayOut])
async def put_presence(
    days: list[PresenceDayPut],
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
) -> list[PresenceDayOut]:
    """Pose / efface des jours de presence du foyer courant (edition manuelle).

    Pour chaque jour :
    - present -> upsert confirmed_present, source=manual
    - absent  -> upsert confirmed_absent, source=manual
    - clear   -> delete la ligne (retour a "incertain")

    Dates futures permises (planifier une absence a l'avance). Idempotent :
    rejouer le meme PUT laisse la base dans le meme etat. Source forcee a
    "manual" — precedence d'ecriture manual > auto_wifi > foyer_init : un
    check-in WiFi ulterieur ne pourra plus ecraser ces jours (cf checkin).

    Retourne l'etat des jours touches (apres upsert ; les jours "clear" ne
    sont pas renvoyes puisque supprimes).
    """
    foyer_uuid = current_foyer_uuid(current)
    touched: list[PresenceDay] = []

    for item in days:
        if item.status not in ("present", "absent", "clear"):
            raise LocalizedHTTPException(
                status_code=400,
                error_code="PRESENCE_INVALID_STATUS_FORMAT",
                format_args={"status": item.status},
            )

        existing = await session.execute(
            select(PresenceDay).where(
                PresenceDay.foyer_id == foyer_uuid,
                PresenceDay.date == item.date,
            )
        )
        row = existing.scalar_one_or_none()

        if item.status == "clear":
            # Effacer => le jour redevient incertain (gris cote client).
            if row is not None:
                await session.delete(row)
            continue

        new_status = (
            "confirmed_present" if item.status == "present" else "confirmed_absent"
        )
        # Select-then-update/insert (meme pattern que checkin / resolve_prompt).
        if row is None:
            row = PresenceDay(
                foyer_id=foyer_uuid,
                date=item.date,
                status=new_status,
                source="manual",
            )
            session.add(row)
        else:
            row.status = new_status
            row.source = "manual"
        touched.append(row)

    await session.flush()

    # L'edition manuelle du calendrier est le chemin par lequel l'utilisateur
    # remplit REELLEMENT ses jours — et c'est celui qui ne refermait rien. Le
    # prompt reclamait donc indefiniment des jours deja renseignes.
    from datetime import datetime as _dt
    aujourdhui = _dt.now(timezone.utc).date()
    if not await _find_uncertain_days(session, foyer_uuid, aujourdhui):
        await _close_stale_presence_prompts(session, foyer_uuid)

    await session.commit()
    return [PresenceDayOut.model_validate(r) for r in touched]


@router.post("/presence/checkin")
async def checkin(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Signal positif : l'user est a la maison (WiFi maison detecte).

    Insere confirmed_present pour aujourd'hui, detecte les jours incertains,
    et genere un prompt RESOLVE_PRESENCE si besoin. Retourne {"ok": true}.
    """
    foyer_uuid = current_foyer_uuid(current)
    today = _today_local()

    # 1. Upsert confirmed_present pour aujourd'hui
    existing = await session.execute(
        select(PresenceDay).where(
            PresenceDay.foyer_id == foyer_uuid,
            PresenceDay.date == today,
        )
    )
    day_row = existing.scalar_one_or_none()
    if day_row is None:
        session.add(PresenceDay(
            foyer_id=foyer_uuid,
            date=today,
            status="confirmed_present",
            source="auto_wifi",
        ))
    elif day_row.source == "manual":
        # Precedence d'ecriture : manual > auto_wifi > foyer_init. Un jour
        # pose explicitement a la main (calendrier / prompt) n'est jamais
        # ecrase par un check-in WiFi automatique, meme si le signal WiFi
        # contredit le statut manuel. On laisse la ligne intacte.
        pass
    elif day_row.status != "confirmed_present":
        # Source non-manuelle (auto_wifi / foyer_init) marquee absent :
        # un signal WiFi positif est fiable, la presence l'emporte.
        day_row.status = "confirmed_present"
        day_row.source = "auto_wifi"

    await session.flush()

    # 2. Detecter les jours incertains
    uncertain = await _find_uncertain_days(session, foyer_uuid, today)

    # 3. Generer un prompt RESOLVE_PRESENCE si des jours sont incertains —
    # et le refermer s'il n'y en a plus. La branche `else` est le defaut
    # corrige le 2026-08-23 : sans elle, un prompt survivait au remplissage
    # des jours qu'il reclamait.
    if uncertain:
        await _ensure_resolve_prompt(session, foyer_uuid, uncertain, today)
    else:
        await _close_stale_presence_prompts(session, foyer_uuid)

    # 4. P4 — proposer le module Absences si le foyer revient d'une absence.
    #
    # Le declenchement se fait AU RETOUR et non pendant : proposer une
    # fonctionnalite de gestion d'absence a quelqu'un qui est justement absent,
    # c'est l'interruption en vacances que tout le chantier cherche a eviter.
    # Le check-in WiFi est precisement le signal "on est rentres".
    #
    # Les jours de presence continuent d'etre enregistres module eteint — c'est
    # une donnee qui s'accumule, pas une interruption (decision 1). C'est ce
    # qui rend la detection possible ici.
    try:
        absent_days = await count_absent_days_since(
            session, foyer_uuid, today - timedelta(days=15), today
        )
        if absent_days >= ABSENCE_PROPOSAL_THRESHOLD_DAYS:
            await propose_module(
                session, foyer_uuid, "presence", reason="absence_detected"
            )
    except Exception:  # noqa: BLE001 - une proposition ne casse jamais un check-in
        pass

    await session.commit()
    return {"ok": True}


async def _find_uncertain_days(
    session: AsyncSession,
    foyer_id: uuid.UUID,
    today: date,
) -> list[date]:
    """Retourne les jours sans ligne dans presence_days entre le dernier
    jour confirme et hier (inclus). Retourne [] si aucun jour incertain.
    """
    yesterday = today - timedelta(days=1)

    # Trouver le dernier jour confirme avant aujourd'hui
    last_confirmed_result = await session.execute(
        select(PresenceDay.date)
        .where(
            PresenceDay.foyer_id == foyer_id,
            PresenceDay.date < today,
        )
        .order_by(PresenceDay.date.desc())
        .limit(1)
    )
    last_confirmed_date = last_confirmed_result.scalar_one_or_none()

    if last_confirmed_date is None:
        # Aucun historique de presence — pas de jours incertains a resoudre
        # (on ne peut pas demander a l'user de resoudre depuis la nuit des temps).
        return []

    # Jours entre last_confirmed_date+1 et yesterday (inclus)
    start = last_confirmed_date + timedelta(days=1)
    if start > yesterday:
        return []  # pas de trou

    # Charger les jours deja resolus dans la fenetre
    resolved_result = await session.execute(
        select(PresenceDay.date).where(
            PresenceDay.foyer_id == foyer_id,
            PresenceDay.date >= start,
            PresenceDay.date <= yesterday,
        )
    )
    resolved_dates = {row[0] for row in resolved_result.all()}

    # Les jours sans ligne sont incertains
    uncertain = []
    cursor = start
    while cursor <= yesterday:
        if cursor not in resolved_dates:
            uncertain.append(cursor)
        cursor += timedelta(days=1)

    return uncertain


async def _close_stale_presence_prompts(
    session: AsyncSession,
    foyer_id: uuid.UUID,
) -> int:
    """Referme les prompts de présence qui n'ont plus d'objet.

    Appelé UNIQUEMENT quand `_find_uncertain_days` ne rend plus rien : on ne
    referme jamais sur une supposition, seulement sur la mesure qui dit que la
    question ne se pose plus.

    Sans ça, un prompt créé alors que des jours manquaient survit à leur
    remplissage, et l'app le réaffiche à chaque ouverture — le seul chemin qui
    le refermait était la réponse à la pop-up, jamais l'édition du calendrier.
    """
    en_attente = (await session.execute(
        select(UserPrompt).where(
            UserPrompt.foyer_id == foyer_id,
            UserPrompt.type == PromptType.RESOLVE_PRESENCE,
            UserPrompt.resolved_at.is_(None),
        )
    )).scalars().all()

    maintenant = datetime.now(timezone.utc)
    for prompt in en_attente:
        prompt.resolved_at = maintenant
        # ANSWERED et non DISMISSED : les jours ONT été renseignés, par un
        # autre chemin. DISMISSED dirait que l'utilisateur a refusé.
        prompt.resolution = PromptResolution.ANSWERED
    if en_attente:
        await session.flush()
    return len(en_attente)


async def _ensure_resolve_prompt(
    session: AsyncSession,
    foyer_id: uuid.UUID,
    uncertain_days: list[date],
    today: date,
) -> None:
    """Cree un prompt RESOLVE_PRESENCE par user du foyer (si pas deja pending).

    Si un prompt RESOLVE_PRESENCE pending existe deja pour un user, on le met
    a jour avec les jours incertains actualises.
    """
    # Determiner le mode
    nb = len(uncertain_days)
    if nb > CALENDAR_MAX_DAYS:
        mode = "simplified"
    else:
        mode = "calendar"

    last_confirmed_date = uncertain_days[0] - timedelta(days=1)

    context = {
        "mode": mode,
        "last_confirmed_date": last_confirmed_date.isoformat(),
    }
    if mode == "calendar":
        context["uncertain_days"] = [d.isoformat() for d in uncertain_days]
    else:
        context["uncertain_days_count"] = nb

    # Users du foyer
    foyer_users = await session.execute(
        select(UserFoyer.user_id).where(UserFoyer.foyer_id == foyer_id)
    )
    user_ids = [row[0] for row in foyer_users.all()]

    for uid in user_ids:
        # Check si un prompt RESOLVE_PRESENCE est deja pending
        existing_result = await session.execute(
            select(UserPrompt).where(
                UserPrompt.user_id == uid,
                UserPrompt.foyer_id == foyer_id,
                UserPrompt.type == PromptType.RESOLVE_PRESENCE,
                UserPrompt.resolved_at.is_(None),
            ).limit(1)
        )
        existing = existing_result.scalar_one_or_none()

        if existing is not None:
            # Mettre a jour le contexte (les jours incertains ont pu changer)
            existing.context = context
        else:
            session.add(UserPrompt(
                user_id=uid,
                foyer_id=foyer_id,
                type=PromptType.RESOLVE_PRESENCE,
                priority=10,  # priorite max
                context=context,
            ))

    await session.flush()
