"""Service vote de frequence (v0.27 : 12 mois + collapse-on-write).

Forme canonique d'un vote : `days_per_month: dict[str(1..12), int]` — 12
entrees, une par mois. Un vote "Simple" (mode interval cote UI) ecrit la
meme valeur sur les 12 mois. Un vote "Selon le mois" ecrit des valeurs
distinctes.

Le consensus est calcule comme la moyenne arrondie par mois sur tous les
votes de la tache. Apres calcul, on applique un collapse-on-write :
- 12 valeurs identiques -> task.recurrence_rule = {type:"interval",
  every_n_days: N}
- valeurs distinctes -> task.recurrence_rule = {type:"monthly_variable",
  days_per_month: {"1": ..., "12": ...}}

Cette regle de collapse rend le format affiche conforme au mental model
("Tous les N jours" si stable, "Variable selon le mois" sinon) sans
toucher la forme canonique des votes.
"""
from __future__ import annotations

from typing import Iterable
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.task import Task
from app.models.task_frequency_vote import TaskFrequencyVote
from app.utils.changelog import record_change

MONTHS = [str(m) for m in range(1, 13)]   # ["1", "2", ..., "12"]


def expand_interval(every_n_days: int) -> dict[str, int]:
    """Construit un dict 12-mois avec la meme valeur partout."""
    return {m: every_n_days for m in MONTHS}


def normalize_days_per_month(value: dict | None) -> dict[str, int]:
    """Verifie/coerce un dict en forme canonique 12-mois (cles "1".."12").

    Accepte :
    - dict[str, int] complet ("1".."12") -> retourne tel quel (cast int).
    - dict[int, int] -> conversion des cles en str.
    - dict partiel -> remplit les manquants avec la moyenne des presents.
    Leve ValueError si valeurs invalides ou dict vide / None.
    """
    if not value:
        raise ValueError("days_per_month vide ou null")
    str_keys: dict[str, int] = {}
    for k, v in value.items():
        sk = str(k)
        if sk not in MONTHS:
            raise ValueError(f"cle de mois invalide : {k!r}")
        try:
            iv = int(v)
        except (TypeError, ValueError) as exc:
            raise ValueError(f"valeur non-entiere pour mois {sk}: {v!r}") from exc
        if not (1 <= iv <= 365):
            raise ValueError(f"every_n_days hors [1, 365] pour mois {sk}: {iv}")
        str_keys[sk] = iv
    if not str_keys:
        raise ValueError("days_per_month vide apres normalisation")
    # Si dict partiel, completer avec la moyenne arrondie des presents.
    if len(str_keys) < 12:
        avg = round(sum(str_keys.values()) / len(str_keys))
        for m in MONTHS:
            str_keys.setdefault(m, avg)
    return {m: str_keys[m] for m in MONTHS}


def average_per_month(votes: Iterable[dict[str, int]]) -> dict[str, int]:
    """Moyenne arrondie par mois sur N votes (chacun deja en forme 12-mois)."""
    sums = {m: 0 for m in MONTHS}
    n = 0
    for v in votes:
        for m in MONTHS:
            sums[m] += v[m]
        n += 1
    if n == 0:
        # Devrait pas arriver (au moins 1 vote par tache recurrente).
        return {m: 7 for m in MONTHS}
    return {m: round(sums[m] / n) for m in MONTHS}


def build_recurrence_rule(consensus: dict[str, int]) -> dict:
    """Collapse-on-write : forme la plus simple possible du recurrence_rule.

    - Si les 12 valeurs sont egales -> {type:"interval", every_n_days: N}
    - Sinon -> {type:"monthly_variable", days_per_month: {...}}
    """
    values = [consensus[m] for m in MONTHS]
    if all(v == values[0] for v in values):
        return {"type": "interval", "every_n_days": int(values[0])}
    return {"type": "monthly_variable", "days_per_month": dict(consensus)}


async def upsert_vote_and_sync_consensus(
    session: AsyncSession,
    task: Task,
    user_id: UUID,
    days_per_month: dict[str, int],
    foyer_id: UUID | str | None = None,
) -> tuple[dict | None, dict]:
    """Upsert le vote de l'user puis recalcule + ecrit le consensus dans
    `task.recurrence_rule` (collapse-on-write).

    `days_per_month` doit etre normalise par le caller via
    `normalize_days_per_month` (validation centralisee).

    Si `foyer_id` est fourni, logge un `task_frequency_votes/VOTE` dans
    change_log pour la dedup vote/recurrence cote /api/history.

    Retourne `(rule_before, rule_after)`. Ne commit pas — caller controle.
    """
    rule_before = dict(task.recurrence_rule) if task.recurrence_rule else None

    # 1. Upsert le vote de l'user.
    vote_r = await session.execute(
        select(TaskFrequencyVote).where(
            TaskFrequencyVote.task_id == task.id,
            TaskFrequencyVote.user_id == user_id,
        )
    )
    vote = vote_r.scalar_one_or_none()
    if vote:
        vote.days_per_month = days_per_month
    else:
        session.add(
            TaskFrequencyVote(
                task_id=task.id,
                user_id=user_id,
                days_per_month=days_per_month,
            )
        )
    await session.flush()

    # 2. Charger tous les votes pour calcul du consensus par mois.
    all_r = await session.execute(
        select(TaskFrequencyVote.days_per_month).where(
            TaskFrequencyVote.task_id == task.id
        )
    )
    all_votes = [normalize_days_per_month(row[0]) for row in all_r.all()]
    consensus = average_per_month(all_votes)
    new_rule = build_recurrence_rule(consensus)

    task.recurrence_rule = new_rule
    task.version += 1

    # 3. Log change pour dedup historique.
    if foyer_id is not None:
        await record_change(
            session, "task_frequency_votes", task.id, "VOTE", str(user_id),
            foyer_id=str(foyer_id),
            new_data={
                "days_per_month": days_per_month,
                "rule_before": rule_before,
                "rule_after": new_rule,
            },
        )

    return rule_before, new_rule
