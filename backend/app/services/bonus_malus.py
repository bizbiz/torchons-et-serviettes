"""Systeme bonus/malus points (v1.0) — calcul du multiplicateur par completion.

Le multiplicateur s'applique sur `base_points` AVANT le split 'fait ensemble'.
Resultat stocke dans task_completions.multiplier + reason (immuable).

Regles (cf design review bonus/malus) :

1. Tache ponctuelle (pas de recurrence_rule) : pas de bonus/malus, mult=1.0.
2. Premiere completion de la tache : bypass complet, mult=1.0, reason=FIRST_COMPLETION.
3. Calcul base sur `completed_at` (pas today) pour permettre le backdate.
4. Zone malus : mult rampe lineaire de 1.0 (au seuil) a plancher_malus (a 100% avance).
5. Zone bonus : mult cliff fixe 1.0 + bonus_pct (cf config foyer).
6. Zone plateau (entre seuils) : mult = 1.0.
7. Tolerance (STRICT/NORMAL/SOUPLE) module les seuils, pas les zones d'affichage.

Floor minimum : 1 pt (sauf tache perso, qui reste 0 par design existant).
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import date, datetime, timezone

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.completion import TaskCompletion
from app.models.enums import CompletionReason, TaskTolerance
from app.models.foyer_bonus_config import FoyerBonusConfig
from app.models.task import Task
from app.models.task_assignment import TaskAssignment
from app.models.task_effort import TaskEffort
from app.services.recurrence import compute_due_date, interval_hours


@dataclass(frozen=True)
class MultiplierResult:
    """Resultat du calcul bonus/malus pour une completion."""

    multiplier: float
    reason: CompletionReason
    # Metadonnees pour le preview UI — non persistees.
    days_early: int  # positif si faite en avance, 0 sinon
    days_late: int  # positif si faite en retard, 0 sinon
    pct_cycle: float  # fraction du cycle (positif = retard, negatif = avance)


def _seuil_malus(tolerance: TaskTolerance, config: FoyerBonusConfig) -> float:
    """Seuil d'entree en zone malus selon tolerance (fraction du cycle en avance)."""
    if tolerance == TaskTolerance.STRICT:
        return config.seuil_malus_strict
    if tolerance == TaskTolerance.SOUPLE:
        return config.seuil_malus_souple
    return config.seuil_malus_normal


def _seuil_bonus(tolerance: TaskTolerance, config: FoyerBonusConfig) -> float:
    """Seuil d'entree en zone bonus selon tolerance (fraction du cycle en retard)."""
    if tolerance == TaskTolerance.STRICT:
        return config.seuil_bonus_strict
    if tolerance == TaskTolerance.SOUPLE:
        return config.seuil_bonus_souple
    return config.seuil_bonus_normal


def compute_multiplier(
    *,
    recurrence_rule: dict | None,
    tolerance: TaskTolerance,
    last_completed_before_this: datetime | None,
    completion_count_before_this: int,
    completed_at: datetime,
    config: FoyerBonusConfig,
) -> MultiplierResult:
    """Calcule le multiplicateur bonus/malus pour une completion.

    Parameters
    ----------
    recurrence_rule : dict | None
        Regle de recurrence de la tache (None pour ponctuelles).
    tolerance : TaskTolerance
        Tolerance de la tache (STRICT/NORMAL/SOUPLE).
    last_completed_before_this : datetime | None
        Derniere completion ACTIVE avant celle-ci (soft-deletes exclues).
        None si aucune : c'est la 1ere completion.
    completion_count_before_this : int
        Nombre de completions actives deja existantes (avant celle-ci).
        0 si c'est la 1ere.
    completed_at : datetime
        Date a laquelle la completion a ete faite (peut etre passee si
        backdate via le date picker).
    config : FoyerBonusConfig
        Config du foyer (seuils, plancher, bonus_pct).

    Returns
    -------
    MultiplierResult
        Multiplicateur + raison + metadonnees pour preview UI.
    """
    # 1. Tache ponctuelle : pas d'echeance, pas de bonus/malus.
    if recurrence_rule is None:
        return MultiplierResult(
            multiplier=1.0,
            reason=CompletionReason.NEUTRAL,
            days_early=0,
            days_late=0,
            pct_cycle=0.0,
        )

    # 2. Premiere completion : bypass systematique (pas d'historique pour juger).
    if completion_count_before_this == 0:
        return MultiplierResult(
            multiplier=1.0,
            reason=CompletionReason.FIRST_COMPLETION,
            days_early=0,
            days_late=0,
            pct_cycle=0.0,
        )

    # 3. Calcul du due_date et de l'ecart relatif au cycle.
    hours = interval_hours(recurrence_rule)
    if not (hours > 0 and hours != float("inf")):
        # Recurrence sans intervalle interpretable : on traite comme ponctuelle.
        return MultiplierResult(
            multiplier=1.0,
            reason=CompletionReason.NEUTRAL,
            days_early=0,
            days_late=0,
            pct_cycle=0.0,
        )

    interval_days = hours / 24.0
    if interval_days <= 0:
        return MultiplierResult(
            multiplier=1.0,
            reason=CompletionReason.NEUTRAL,
            days_early=0,
            days_late=0,
            pct_cycle=0.0,
        )

    # due_date base sur la derniere completion (celle d'avant celle-ci) et
    # sur la recurrence. compute_due_date renvoie None si la regle est
    # invalide — on degrade vers NEUTRAL.
    due = compute_due_date(recurrence_rule, last_completed_before_this)
    if due is None:
        return MultiplierResult(
            multiplier=1.0,
            reason=CompletionReason.NEUTRAL,
            days_early=0,
            days_late=0,
            pct_cycle=0.0,
        )

    # On travaille en jours entiers (cote Python comme cote Android, on
    # affiche des jours). completed_at peut etre timezone-aware — on le
    # convertit en date UTC puisque le modele urgency est UTC.
    if completed_at.tzinfo is None:
        completed_at = completed_at.replace(tzinfo=timezone.utc)
    completed_date = completed_at.astimezone(timezone.utc).date()
    delta_days = (completed_date - due).days  # positif si en retard, negatif si en avance
    pct_cycle = delta_days / interval_days  # fraction du cycle (signe conserve)

    days_early = max(0, -delta_days)
    days_late = max(0, delta_days)

    # 4. Zone bonus (retard).
    if delta_days > 0:
        seuil_b = _seuil_bonus(tolerance, config)
        if pct_cycle >= seuil_b:
            return MultiplierResult(
                multiplier=1.0 + config.bonus_pct,
                reason=CompletionReason.BONUS_RETARD,
                days_early=days_early,
                days_late=days_late,
                pct_cycle=pct_cycle,
            )
        # Retard mais sous le seuil : zone plateau, pas de bonus.
        return MultiplierResult(
            multiplier=1.0,
            reason=CompletionReason.NEUTRAL,
            days_early=days_early,
            days_late=days_late,
            pct_cycle=pct_cycle,
        )

    # 5. Zone malus (avance).
    pct_avance = -pct_cycle  # fraction positive
    seuil_m = _seuil_malus(tolerance, config)
    if pct_avance <= seuil_m:
        # Dans la zone plateau (pas assez en avance pour etre penalise).
        return MultiplierResult(
            multiplier=1.0,
            reason=CompletionReason.NEUTRAL,
            days_early=days_early,
            days_late=days_late,
            pct_cycle=pct_cycle,
        )

    # Rampe lineaire : de 1.0 au seuil_m a plancher_malus a 100% avance.
    # excess = 0 au seuil, 1 a 100% avance (clamp pour surete).
    excess = min(1.0, (pct_avance - seuil_m) / max(1e-9, 1.0 - seuil_m))
    mult = 1.0 - excess * (1.0 - config.plancher_malus)
    mult = max(config.plancher_malus, min(1.0, mult))

    return MultiplierResult(
        multiplier=mult,
        reason=CompletionReason.MALUS_AVANCE,
        days_early=days_early,
        days_late=days_late,
        pct_cycle=pct_cycle,
    )


def apply_multiplier_to_points(base_points: int, result: MultiplierResult) -> int:
    """Applique le multiplicateur sur base_points avec les regles d'arrondi.

    Regles :
    - Tache perso (base_points == 0) : on reste a 0 (pas de bonus/malus).
    - Sinon : round(base_points * multiplier), avec floor minimum 1 pt.

    Cf design review : une completion doit rapporter au moins 1 pt meme avec
    plancher malus applique sur petit weight, sinon l'action parait gratuite.
    """
    if base_points <= 0:
        return 0
    raw = base_points * result.multiplier
    rounded = int(round(raw))
    return max(1, rounded)


async def load_or_create_bonus_config(
    session: AsyncSession, foyer_id: uuid.UUID
) -> FoyerBonusConfig:
    """Charge la config bonus/malus du foyer, cree une ligne par defaut si absente."""
    result = await session.execute(
        select(FoyerBonusConfig).where(FoyerBonusConfig.foyer_id == foyer_id)
    )
    cfg = result.scalar_one_or_none()
    if cfg is None:
        cfg = FoyerBonusConfig(foyer_id=foyer_id)
        session.add(cfg)
        await session.flush()
    return cfg


async def compute_base_points_for_task(session: AsyncSession, task: Task) -> int:
    """Base points d'une tache avant multiplicateur et split.

    0 si tache 'perso' (assignee a 0 ou 1 user), sinon moyenne des
    TaskEffort.points ou fallback sur task.weight. Logique existante pre-
    bonus/malus, extraite ici pour reuse (completions API + sync handler).
    """
    assign_result = await session.execute(
        select(func.count(TaskAssignment.user_id)).where(
            TaskAssignment.task_id == task.id
        )
    )
    num_assigned = assign_result.scalar() or 1
    if num_assigned <= 1:
        return 0
    avg_result = await session.execute(
        select(func.avg(TaskEffort.points)).where(TaskEffort.task_id == task.id)
    )
    avg_effort = avg_result.scalar()
    return int(round(avg_effort)) if avg_effort else task.weight


async def compute_bonus_malus_for_completion(
    session: AsyncSession,
    task: Task,
    foyer_id: uuid.UUID,
    completed_at: datetime,
) -> MultiplierResult:
    """Calcule le multiplicateur pour une completion a venir.

    Fetch depuis la DB : config foyer, count + derniere completion active
    strictement avant `completed_at`. Delegue ensuite au calcul pur
    `compute_multiplier`.
    """
    count_result = await session.execute(
        select(func.count(TaskCompletion.id)).where(
            TaskCompletion.task_id == task.id,
            TaskCompletion.deleted_at.is_(None),
            TaskCompletion.completed_at < completed_at,
        )
    )
    completion_count = count_result.scalar() or 0

    last_result = await session.execute(
        select(func.max(TaskCompletion.completed_at)).where(
            TaskCompletion.task_id == task.id,
            TaskCompletion.deleted_at.is_(None),
            TaskCompletion.completed_at < completed_at,
        )
    )
    last_completed = last_result.scalar()

    config = await load_or_create_bonus_config(session, foyer_id)

    return compute_multiplier(
        recurrence_rule=task.recurrence_rule,
        tolerance=task.tolerance,
        last_completed_before_this=last_completed,
        completion_count_before_this=completion_count,
        completed_at=completed_at,
        config=config,
    )


def resolve_stored_reason_and_mult(
    base_points: int, mult_result: MultiplierResult
) -> tuple[CompletionReason, float]:
    """Determine les valeurs multiplier/reason a persister sur la ligne.

    Tache perso (base_points == 0) : reason=PERSO, mult=1.0 (pas de bonus/
    malus applicable). Sinon : valeurs du MultiplierResult.
    """
    if base_points > 0:
        return mult_result.reason, mult_result.multiplier
    return CompletionReason.PERSO, 1.0


def compute_grace_until(
    *,
    interval_days: float,
    triggered_at: datetime,
    config: FoyerBonusConfig,
) -> datetime:
    """Calcule la fin de grace period apres dispatch d'un bump prompt.

    Y = clamp(K * interval, y_min, y_max). Grace = grace_ratio * Y jours.
    """
    from datetime import timedelta

    y_raw = config.bump_interval_factor_k * interval_days
    y = max(config.bump_y_min_days, min(config.bump_y_max_days, int(round(y_raw))))
    grace_days = config.bump_grace_ratio * y
    return triggered_at + timedelta(days=grace_days)
