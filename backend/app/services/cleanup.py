"""Job daily cleanup pour les tables onboarding sans compte (v0.30).

Trois passes :
  1. Suppression hard des pairing_tokens dont expires_at < now() depuis
     >24h (ils ne servent plus a rien — single-use ou expires).
  2. Marquer foyers fantomes archived_at = now() : aucun member actif
     (left_at IS NULL) ET aucune activite recente (>90j sans creation/
     modification). On ne les delete pas, juste un flag pour la passe (3).
  3. Hard-delete des foyers ou archived_at < now() - 365j. CASCADE sur
     toutes les tables scopees foyer (tasks, completions, sync, etc.).
  4. Anonymize the accounts that left their last household, or were deleted, more than 30
     days ago (services/member_departure.py). Since 2026-11-02 they are no longer deleted:
     deleting failed on any account that had acted, and failed the whole job with it.

Execute par APScheduler a 03h30 UTC (apres archive @03h00 et retard @03h15
pour etaler la charge). Idempotent — un re-run apres redemarrage applique
les memes regles sur l'etat courant sans dupliquer.

Cf. docs/design-onboarding-public.md §9 et §11.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import structlog
from sqlalchemy import and_, delete, func, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import async_session
from app.services.member_departure import anonymize_departed_users
from app.services.prompt_validity import report_stale_candidates
from app.models.foyer import Foyer
from app.models.pairing_token import PairingToken
from app.models.user_foyer import UserFoyer

logger = structlog.get_logger()


# Constantes — exposees pour pouvoir override en tests si besoin.
PAIRING_TOKEN_GRACE_HOURS = 24
FOYER_ARCHIVE_INACTIVE_DAYS = 90
FOYER_PURGE_AFTER_DAYS = 365


async def cleanup_pairing_tokens(session: AsyncSession) -> int:
    """Hard-delete les tokens expires depuis > grace hours."""
    cutoff = datetime.now(timezone.utc) - timedelta(hours=PAIRING_TOKEN_GRACE_HOURS)
    result = await session.execute(
        delete(PairingToken).where(PairingToken.expires_at < cutoff)
    )
    return result.rowcount or 0


async def archive_ghost_foyers(session: AsyncSession) -> int:
    """Marque archived_at = now() pour les foyers ou tous les members ont
    left_at IS NOT NULL (foyer vide actif) et qui n'ont jamais ete
    archived_at avant.

    On ne touche PAS les foyers deja archives (idempotent).
    """
    now = datetime.now(timezone.utc)

    # Sub-select : les foyer_ids ayant au moins un member actif (left_at IS NULL)
    active_member_subq = (
        select(UserFoyer.foyer_id)
        .where(UserFoyer.left_at.is_(None))
        .distinct()
    )

    # Foyers candidats : pas encore archives, pas soft-deleted, et sans member actif
    result = await session.execute(
        update(Foyer)
        .where(
            and_(
                Foyer.archived_at.is_(None),
                Foyer.deleted_at.is_(None),
                Foyer.id.notin_(active_member_subq),
            )
        )
        .values(archived_at=now)
    )
    return result.rowcount or 0


async def purge_old_foyers(session: AsyncSession) -> int:
    """Hard-delete les foyers archived_at < now - PURGE_AFTER_DAYS.

    CASCADE supprime tasks, completions, change_log etc. qui referencent
    ces foyers. Les User qui n'avaient que ces foyers se retrouvent
    avec last_active_foyer_id = NULL (FK ON DELETE SET NULL).
    """
    cutoff = datetime.now(timezone.utc) - timedelta(days=FOYER_PURGE_AFTER_DAYS)
    result = await session.execute(
        delete(Foyer).where(
            and_(
                Foyer.archived_at.isnot(None),
                Foyer.archived_at < cutoff,
            )
        )
    )
    return result.rowcount or 0


async def daily_cleanup_job() -> None:
    """Job APScheduler. Execute en transaction unique pour la consistance."""
    async with async_session() as session:
        try:
            tokens = await cleanup_pairing_tokens(session)
            ghosts = await archive_ghost_foyers(session)
            purged_foyers = await purge_old_foyers(session)
            anonymized = await anonymize_departed_users(session)
            await session.commit()
            stale = await report_stale_candidates(session)
            logger.info(
                "daily_cleanup_done",
                pairing_tokens_deleted=tokens,
                foyers_archived=ghosts,
                foyers_purged=purged_foyers,
                users_anonymized=anonymized,
                stale_prompt_candidates=stale,
            )
        except Exception as e:
            await session.rollback()
            logger.error("daily_cleanup_failed", error=str(e))
            raise
