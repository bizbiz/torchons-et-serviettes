"""Contextual module proposals — P4 (see docs/module-registry.md).

The app proposes enabling a module at the moment a real event justifies it.
This module holds the budget and the creation; the signals themselves live
where they are already computed (a member joining, an absence, a task drifting)
and call `propose_module`.

WHY A SEPARATE BUDGET

The queue already carries five WORK prompt types — the app asking for an
information it needs. Discovery is the opposite direction: the app offering
more. Sharing one counter between the two starves whichever loses, and the
starvation is backwards: on an active foyer, work prompts are generated
continuously and would always win, while a brand-new foyer has no history at
all, so it would receive discovery prompts every two hours. That is precisely
the wrong way round — saturate the beginner, stay invisible to the regular.

Hence two counters in one queue: work prompts keep their 2-hour client
tolerance, discovery gets the limits below, held in the database.

WHY THE DATABASE AND NOT THE CLIENT

The existing tolerance lives in `HomeViewModel`. For discovery that would mean:
a reinstall resets everything and re-proposes all eight modules; two devices
diverge; and both members of a foyer each get the same proposal for what is a
collective decision. `user_prompts` already carries `created_at`,
`resolved_at`, `resolution` and `grace_until` — the budget is a query over
rows that already exist, not a new table.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.enums import PromptResolution, PromptType
from app.models.foyer import Foyer
from app.models.user_foyer import UserFoyer
from app.models.user_prompt import UserPrompt
from app.services.catalog import pick_locale
from app.services.modules import Module, get_registry, is_module_enabled

# --- Budget (decision 5 of the design) ---

#: Nothing before the foyer has lived a week. A proposal on day two lands on
#: someone still discovering the core.
MIN_FOYER_AGE_DAYS = 7
#: Hard floor between two proposals in a foyer, all modules and all members
#: confounded. Was 7 days; lowered to 5 once the usage triggers (see
#: module_usage_triggers.py) made four more modules proposable.
MIN_DAYS_BETWEEN_PROPOSALS = 5
#: Silence on a module after a refusal.
REFUSAL_COOLDOWN_DAYS = 30
#: Two refusals and the module is never proposed again. It stays reachable in
#: Settings, so dropping it costs nothing — that is what makes a hard stop
#: acceptable rather than cruel.
MAX_REFUSALS = 2

DISCOVERY_PRIORITY = 4


async def _foyer_age_days(session: AsyncSession, foyer_id: uuid.UUID) -> float:
    created = await session.scalar(select(Foyer.created_at).where(Foyer.id == foyer_id))
    if created is None:
        return 0.0
    return (datetime.now(timezone.utc) - created).total_seconds() / 86400.0


async def _days_since_last_proposal(
    session: AsyncSession, foyer_id: uuid.UUID
) -> float | None:
    """Days since the last discovery prompt was CREATED in this foyer.

    Counted on creation and not on resolution on purpose: an ignored proposal
    still consumed its slot. Counting resolutions would let an unanswered
    proposal open the door to a new one every cron pass.
    """
    last = await session.scalar(
        select(func.max(UserPrompt.created_at)).where(
            UserPrompt.foyer_id == foyer_id,
            UserPrompt.type == PromptType.DISCOVER_MODULE,
        )
    )
    if last is None:
        return None
    return (datetime.now(timezone.utc) - last).total_seconds() / 86400.0


async def _refusals_for(
    session: AsyncSession, foyer_id: uuid.UUID, module_key: str
) -> tuple[int, float | None]:
    """(number of refusals, days since the last one) for this module."""
    result = await session.execute(
        select(UserPrompt.resolved_at).where(
            UserPrompt.foyer_id == foyer_id,
            UserPrompt.type == PromptType.DISCOVER_MODULE,
            UserPrompt.resolution == PromptResolution.DISMISSED,
            UserPrompt.context["module_key"].astext == module_key,
            # Seuls les refus EXPLICITES comptent. Un client qui ne connait pas
            # ce type de prompt le resout en DISMISSED sans rien afficher : sans
            # ce filtre, l'APK deploye brulerait les deux refus d'un module que
            # personne n'a jamais vu propose, et l'abandonnerait a vie.
            UserPrompt.context["declined"].astext == "true",
        )
    )
    dates = [d for d in result.scalars().all() if d is not None]
    if not dates:
        return 0, None
    newest = max(dates)
    return len(dates), (datetime.now(timezone.utc) - newest).total_seconds() / 86400.0


async def _active_member_ids(
    session: AsyncSession, foyer_id: uuid.UUID
) -> list[uuid.UUID]:
    members = await session.execute(
        # left_at: filtered through role != "former_member", set together by soft_leave_foyer.
        select(UserFoyer.user_id).where(
            UserFoyer.foyer_id == foyer_id,
            UserFoyer.role != "former_member",
        )
    )
    return list(members.scalars().all())


async def _recipients(
    session: AsyncSession, foyer_id: uuid.UUID, module: Module
) -> list[uuid.UUID]:
    """Members a proposal for this module would go to: those who do NOT have it.

    Collective module: its state is foyer-wide, so either nobody is asked (it is
    on) or every active member is. Personal module: `is_module_enabled` called
    without a user reads a foyer-wide row that does not exist for it, and would
    answer from the foyer's grandfathering alone. So each member is asked on
    their own state, and a member who already has the module on is left out.
    """
    members = await _active_member_ids(session, foyer_id)
    if module.is_collective:
        if await is_module_enabled(session, foyer_id, module.key):
            return []
        return members
    return [
        user_id
        for user_id in members
        if not await is_module_enabled(session, foyer_id, module.key, user_id)
    ]


async def can_propose(
    session: AsyncSession, foyer_id: uuid.UUID, module_key: str
) -> bool:
    """Whether a proposal for this module may be created right now."""
    module = get_registry().by_key(module_key)
    if module is None:
        return False

    # Proposing what is already on is noise, and it is the single most likely
    # bug of the whole mechanism: a signal fires for a foyer that adopted the
    # module long ago. A personal module has no foyer-wide state: the question
    # is whether some member still lacks it.
    if module.is_collective:
        if await is_module_enabled(session, foyer_id, module_key):
            return False
    elif not await _recipients(session, foyer_id, module):
        return False

    if await _foyer_age_days(session, foyer_id) < MIN_FOYER_AGE_DAYS:
        return False

    since_last = await _days_since_last_proposal(session, foyer_id)
    if since_last is not None and since_last < MIN_DAYS_BETWEEN_PROPOSALS:
        return False

    refusals, since_refusal = await _refusals_for(session, foyer_id, module_key)
    if refusals >= MAX_REFUSALS:
        return False
    if since_refusal is not None and since_refusal < REFUSAL_COOLDOWN_DAYS:
        return False

    return True


async def propose_module(
    session: AsyncSession,
    foyer_id: uuid.UUID,
    module_key: str,
    reason: str,
    locale: str = "fr",
) -> int:
    """Create a proposal for every active member. Returns how many were created.

    Fire-and-forget from the signal sites: returns 0 rather than raising when
    the budget refuses, so a proposal never breaks the action that revealed the
    signal (a completion, someone joining, a presence check-in).

    The copy is frozen into `context` at creation, like every other prompt type
    in this queue. It comes from modules.yaml — the same source that feeds the
    Features screen and, later, the site cards.
    """
    if not await can_propose(session, foyer_id, module_key):
        return 0

    module = get_registry().by_key(module_key)
    if module is None:
        return 0

    user_ids = await _recipients(session, foyer_id, module)
    if not user_ids:
        return 0

    context = {
        "module_key": module.key,
        "title": pick_locale(module.title, locale),
        "pitch": pick_locale(module.pitch, locale),
        "details": pick_locale(module.details, locale),
        "example": pick_locale(module.example, locale) if module.example else None,
        # The guide page rides with the proposal. Frozen here like the other copy
        # fields; prompts created before this change lack both keys, and clients
        # must tolerate their absence.
        "guide_why": pick_locale(module.guide_why, locale),
        "guide_how": pick_locale(module.guide_how, locale),
        "reason": reason,
        "scope": module.scope,
    }
    # A proposal that nobody answers should not sit in the queue forever: it
    # would block the foyer's slot on its own, since the budget counts creation.
    expires_at = datetime.now(timezone.utc) + timedelta(days=MIN_DAYS_BETWEEN_PROPOSALS)

    for user_id in user_ids:
        session.add(
            UserPrompt(
                user_id=user_id,
                foyer_id=foyer_id,
                type=PromptType.DISCOVER_MODULE,
                priority=DISCOVERY_PRIORITY,
                context=context,
                expires_at=expires_at,
            )
        )
    return len(user_ids)
