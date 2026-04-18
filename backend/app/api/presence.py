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

from fastapi import APIRouter, Depends
from sqlalchemy import and_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.models.enums import PromptType
from app.models.presence_day import PresenceDay
from app.models.user_foyer import UserFoyer
from app.models.user_prompt import UserPrompt
from app.utils.scoping import current_foyer_uuid

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
    elif day_row.status != "confirmed_present":
        # Un jour marque absent manuellement mais maintenant on a un signal
        # WiFi positif : la presence l'emporte.
        day_row.status = "confirmed_present"
        day_row.source = "auto_wifi"

    await session.flush()

    # 2. Detecter les jours incertains
    uncertain = await _find_uncertain_days(session, foyer_uuid, today)

    # 3. Generer un prompt RESOLVE_PRESENCE si des jours sont incertains
    if uncertain:
        await _ensure_resolve_prompt(session, foyer_uuid, uncertain, today)

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
