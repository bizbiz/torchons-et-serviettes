"""Un prompt dont la condition a disparu se referme avant d'être servi.

POURQUOI CE MODULE EXISTE

Un prompt est créé quand une condition devient vraie ; rien ne garantit qu'elle
le reste. Entre sa création et sa lecture, l'utilisateur a pu résoudre le
problème par un autre chemin — remplir son calendrier depuis l'écran Présence,
activer un module depuis les Réglages, supprimer la tâche concernée.

Le défaut est structurel, pas ponctuel : `if condition: creer_prompt(...)`
s'écrit naturellement sans `else`, et **une branche absente ne s'écrit nulle
part**, donc aucune relecture de code ne la signale. Mesuré en production le
2026-08-23 : une pop-up réclamait huit jours de présence qui étaient tous
renseignés depuis une semaine, et revenait à chaque ouverture de l'app.

CE QUE FAIT CE MODULE, ET CE QU'IL NE FAIT PAS

Il centralise la question « cette condition tient-elle encore ? », posée au
moment de SERVIR le prompt — le seul instant où la réponse compte, et le seul
qui ne dépende pas d'un chemin d'écriture particulier. Corriger chaque
producteur aurait demandé de retrouver tous les chemins qui invalident chaque
condition ; les valider à la lecture n'en demande aucun.

Il ne devine jamais : un type sans validateur déclaré est laissé tel quel. Un
prompt refermé à tort est pire qu'un prompt qui traîne — il fait disparaître
une question que l'utilisateur devait voir.
"""

from __future__ import annotations

import uuid
from datetime import date, datetime, timezone
from typing import Awaitable, Callable

import structlog
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.enums import PromptResolution, PromptType
from app.models.task import Task
from app.models.user_prompt import UserPrompt
from app.services.recurrence import is_votable

logger = structlog.get_logger()

#: Un validateur rend True si la condition du prompt TIENT ENCORE.
Validator = Callable[[AsyncSession, UserPrompt], Awaitable[bool]]


async def _presence_still_uncertain(session: AsyncSession, prompt: UserPrompt) -> bool:
    """Reste-t-il des jours de présence non renseignés ?

    Import local : `presence` importe ce module pour refermer après un
    check-in, l'importer en tête créerait un cycle.
    """
    from app.api.presence import _find_uncertain_days

    jours = await _find_uncertain_days(
        session, prompt.foyer_id, datetime.now(timezone.utc).date()
    )
    return bool(jours)


async def _task_still_exists(session: AsyncSession, prompt: UserPrompt) -> bool:
    """La tâche visée par le prompt est-elle encore vivante ?

    Un prompt qui parle d'une tâche supprimée ne peut plus rien produire : le
    répondre écrirait sur une ligne absente, et l'afficher demande à
    l'utilisateur de se prononcer sur ce qu'il vient d'effacer.
    """
    brut = (prompt.context or {}).get("task_id")
    if not brut:
        # Pas d'identifiant : on ne sait pas juger, donc on ne juge pas.
        return True
    try:
        task_id = uuid.UUID(str(brut))
    except (ValueError, AttributeError):
        return True

    vivante = (await session.execute(
        select(Task.id).where(Task.id == task_id, Task.deleted_at.is_(None))
    )).scalar_one_or_none()
    return vivante is not None


async def _task_still_votable(session: AsyncSession, prompt: UserPrompt) -> bool:
    """The task is alive AND its rule still accepts a frequency vote.

    A vote requested before the task switched to a date-anchored rule would
    otherwise be answered, and refused by `PUT /tasks/{id}/frequency`.
    """
    if not await _task_still_exists(session, prompt):
        return False
    brut = (prompt.context or {}).get("task_id")
    try:
        task_id = uuid.UUID(str(brut))
    except (ValueError, AttributeError, TypeError):
        return True
    regle = (await session.execute(
        select(Task.recurrence_rule).where(Task.id == task_id)
    )).scalar_one_or_none()
    return is_votable(regle)


#: Types dont la condition est revalidable. Un type ABSENT de ce registre est
#: servi sans contrôle — c'est volontaire : n'y ajouter que ce qu'on sait
#: juger, jamais une heuristique.
#:
#: DISCOVER_MODULE n'y figure pas : sa revalidation est déjà faite en SQL dans
#: `GET /prompts/next`, et deux implémentations de la même règle divergent à la
#: première correction.
VALIDATORS: dict[PromptType, Validator] = {
    PromptType.RESOLVE_PRESENCE: _presence_still_uncertain,
    PromptType.BUMP_EFFORT: _task_still_exists,
    PromptType.VOTE_FREQUENCY: _task_still_votable,
}


async def close_if_stale(session: AsyncSession, prompt: UserPrompt) -> bool:
    """Referme le prompt si sa condition a disparu. Rend True s'il l'a été.

    Journalise chaque fermeture : c'est le seul signal qui rend visible un
    producteur qui crée des prompts sans surveiller leur péremption. Sans
    trace, le mécanisme se contenterait de masquer le symptôme.
    """
    validateur = VALIDATORS.get(prompt.type)
    if validateur is None:
        return False

    try:
        if await validateur(session, prompt):
            return False
    except Exception as exc:  # noqa: BLE001
        # Un validateur en panne ne doit jamais faire disparaître un prompt :
        # en cas de doute on sert, et on le dit.
        logger.warning(
            "prompt_validator_failed",
            prompt_type=prompt.type.value,
            prompt_id=str(prompt.id),
            error=str(exc),
        )
        return False

    age_jours = None
    if prompt.created_at:
        cree = prompt.created_at
        if cree.tzinfo is None:
            cree = cree.replace(tzinfo=timezone.utc)
        age_jours = (datetime.now(timezone.utc) - cree).days

    prompt.resolved_at = datetime.now(timezone.utc)
    # ANSWERED et non DISMISSED : la question a bien reçu sa réponse, par un
    # autre chemin. DISMISSED dirait que l'utilisateur a refusé.
    prompt.resolution = PromptResolution.ANSWERED
    logger.info(
        "prompt_stale_closed",
        prompt_type=prompt.type.value,
        prompt_id=str(prompt.id),
        foyer_id=str(prompt.foyer_id),
        age_days=age_jours,
    )
    return True


async def report_stale_candidates(session: AsyncSession, seuil_jours: int = 14) -> int:
    """Journalise les prompts anciens dont on ne sait PAS juger la condition.

    Le registre ci-dessus ne couvre que ce qu'on sait revalider. Pour tout le
    reste, la seule chose honnête est de rendre l'ancienneté visible : un
    prompt non résolu depuis des semaines est soit légitime (une grâce longue),
    soit le symptôme du même défaut sur un type qu'on n'a pas encore traité.

    Ne referme rien — cette fonction observe, elle ne décide pas.
    """
    limite = datetime.now(timezone.utc) - __import__("datetime").timedelta(days=seuil_jours)
    anciens = (await session.execute(
        select(UserPrompt).where(
            UserPrompt.resolved_at.is_(None),
            UserPrompt.created_at < limite,
            UserPrompt.type.notin_(list(VALIDATORS.keys())),
        )
    )).scalars().all()

    for prompt in anciens:
        logger.info(
            "prompt_stale_candidate",
            prompt_type=prompt.type.value,
            prompt_id=str(prompt.id),
            foyer_id=str(prompt.foyer_id),
            created_at=prompt.created_at.isoformat() if prompt.created_at else None,
            grace_until=prompt.grace_until.isoformat() if prompt.grace_until else None,
        )
    return len(anciens)
