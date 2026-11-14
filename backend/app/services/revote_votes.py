"""Votes read around a re-vote request: the snapshot at request time, the answer at resolve time.

A re-vote request stores the target's current vote in the prompt context (`vote_at_request`).
When the target answers, the new value comes from the resolve payload when the app sends it
(`points` for an effort, `days_per_month` for a frequency), else from the database.

Why the payload: on the target's phone the frequency vote goes through REST and, if that
fails, through the offline /sync queue, while the resolve is sent anyway. The vote can
therefore be written AFTER the resolve; the app knows what it voted, the server may not yet.
Effort is a REST call made just before the resolve (HomeViewModel.submitPromptEffortVote).
"""

from __future__ import annotations

from uuid import UUID

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.enums import PromptType
from app.models.task_effort import TaskEffort
from app.models.task_frequency_vote import TaskFrequencyVote
from app.services.frequency_vote import normalize_days_per_month

REVOTE_TYPES = (PromptType.VOTE_EFFORT, PromptType.VOTE_FREQUENCY)
SNAPSHOT_KEY = "vote_at_request"


async def current_vote(session: AsyncSession, task_id: UUID, user_id: UUID, kind: str):
    """The user's vote on the task: effort points, or a 12-month {"1": days} dict; None if none."""
    if kind == "effort":
        return (await session.execute(
            select(TaskEffort.points).where(TaskEffort.task_id == task_id, TaskEffort.user_id == user_id)
        )).scalar_one_or_none()
    stored = (await session.execute(
        select(TaskFrequencyVote.days_per_month).where(
            TaskFrequencyVote.task_id == task_id, TaskFrequencyVote.user_id == user_id)
    )).scalar_one_or_none()
    return normalize_days_per_month(stored) if stored else None


def vote_from_payload(kind: str, payload: dict):
    """The vote an app carries in the resolve payload; None when absent or invalid."""
    try:
        if kind == "effort":
            points = int(payload["points"])
            return points if 1 <= points <= 18 else None
        return normalize_days_per_month(payload["days_per_month"])
    except (KeyError, TypeError, ValueError):
        return None


async def answer_votes(session: AsyncSession, prompt, responder_id: UUID, payload: dict):
    """(old, new) for the answer notification. Both None when the request predates the snapshot."""
    ctx = prompt.context or {}
    if SNAPSHOT_KEY not in ctx:
        return None, None
    kind = ctx.get("kind", "effort")
    old = ctx[SNAPSHOT_KEY]
    if kind == "frequency" and old:
        old = normalize_days_per_month(old)
    new = vote_from_payload(kind, payload)
    if new is None:
        new = await current_vote(session, UUID(ctx["task_id"]), responder_id, kind)
    return old, new


async def revote_answer_args(session, prompt, responder_id, resolution, payload) -> dict | None:
    """Arguments of send_revote_answer, None when the prompt is no re-vote request."""
    ctx = prompt.context or {}
    if prompt.type not in REVOTE_TYPES or not ctx.get("requester_user_id"):
        return None
    old, new = await answer_votes(session, prompt, responder_id, payload)
    return {
        "requester_id": UUID(ctx["requester_user_id"]), "responder_id": responder_id,
        "task_id": UUID(ctx["task_id"]), "kind": ctx.get("kind", "effort"),
        "resolution": "answered" if resolution == "ANSWERED" else "dismissed",
        "old": old, "new": None if resolution != "ANSWERED" else new,
    }
