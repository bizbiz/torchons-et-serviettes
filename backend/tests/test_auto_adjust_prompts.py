"""Auto-adjust module: re-vote requests and the "chronically late" effort bump.

Neither path had a single test before 2026-11-03 (the header of
test_frequency_vote_guard.py listed request-revote as "not covered").

Paths, and what covers each:
- POST /prompts/request-revote, module on: the target is served the prompt by
  GET /prompts/next; duplicate (409), self (400), foreign task (404) refused.
- POST /prompts/request-revote, module off: the server still accepts it, and
  GET /prompts/next does NOT serve it. Kept as a witness of WHY the app hides
  the button while the module is off (FrequencyInfoDialog / EffortInfoDialog):
  if the server ever starts serving or refusing, this test says so.
- generate_bump_prompts: module on -> one BUMP_EFFORT per active member; module
  off -> a DISCOVER_MODULE proposal for auto_adjust instead; below the late
  ratio -> nothing.
- Grace period per member (2026-11-19): « Plus tard » (action "later") is asked
  again on the next late completion only, the others keep their grace.
Not covered: the client side (dialogs, home modal), left to the emulator run.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import select, update

from app.models.enums import CompletionReason, PromptType
from tests.module_helpers import house, prompts_of, set_module

WEEKLY = {"type": "interval", "every_n_days": 7}


async def _make_weekly(h) -> None:
    from app.database import async_session
    from app.models.task import Task

    async with async_session() as session:
        await session.execute(
            update(Task).where(Task.id == h.task_id).values(recurrence_rule=WEEKLY)
        )
        await session.commit()


async def _ask(client, h, kind: str = "frequency", target: int = 1, task_id=None):
    return await client.post(
        "/api/prompts/request-revote",
        json={
            "target_user_id": str(h.user_ids[target]),
            "task_id": str(task_id or h.task_id),
            "kind": kind,
        },
        headers=h.headers(0),
    )


async def _late_completions(h, late: int, neutral: int) -> None:
    from app.database import async_session
    from app.models.completion import TaskCompletion

    # Ages 7 to 28 days: far inside the 90-day analysis window at any hour, and
    # never on today's UTC date, so the clock cannot move a row across a bound.
    now = datetime.now(timezone.utc)
    async with async_session() as session:
        for i in range(late + neutral):
            session.add(
                TaskCompletion(
                    task_id=h.task_id,
                    completed_by=h.user_ids[0],
                    completed_at=now - timedelta(days=7 * (i + 1)),
                    reason=CompletionReason.BONUS_RETARD if i < late else CompletionReason.NEUTRAL,
                )
            )
        await session.commit()


async def _bump(h, late: bool = False) -> None:
    from app.database import async_session
    from app.models.task import Task
    from app.services.prompts import generate_bump_prompts

    async with async_session() as session:
        task = await session.get(Task, h.task_id)
        await generate_bump_prompts(session, task, h.foyer_id, just_completed_late=late)
        await session.commit()


async def _bump_prompts(h) -> list:
    from app.database import async_session
    from app.models.user_prompt import UserPrompt

    async with async_session() as session:
        rows = await session.execute(
            select(UserPrompt).where(
                UserPrompt.foyer_id == h.foyer_id,
                UserPrompt.type == PromptType.BUMP_EFFORT,
            )
        )
        return list(rows.scalars().all())


@pytest.mark.asyncio
@pytest.mark.parametrize("kind, expected_type", [
    ("frequency", "VOTE_FREQUENCY"),
    ("effort", "VOTE_EFFORT"),
])
async def test_revote_request_reaches_the_target_when_module_is_on(client, kind, expected_type):
    async with house() as h:
        await _make_weekly(h)
        await set_module(h, "auto_adjust", True)

        resp = await _ask(client, h, kind)
        assert resp.status_code == 200, resp.text
        assert resp.json()["type"] == expected_type

        served = await client.get("/api/prompts/next", headers=h.headers(1))
        assert served.status_code == 200, served.text
        body = served.json()
        assert body["type"] == expected_type
        assert body["context"]["task_id"] == str(h.task_id)
        assert body["context"]["requester_user_id"] == str(h.user_ids[0])

        # The requester is not asked anything.
        mine = await client.get("/api/prompts/next", headers=h.headers(0))
        assert mine.status_code == 204, mine.text


@pytest.mark.asyncio
async def test_second_request_is_refused_while_the_first_is_pending(client):
    async with house() as h:
        await _make_weekly(h)
        await set_module(h, "auto_adjust", True)
        assert (await _ask(client, h)).status_code == 200
        again = await _ask(client, h)
        assert again.status_code == 409, again.text
        assert again.json()["detail"]["code"] == "PROMPT_ALREADY_PENDING"


@pytest.mark.asyncio
async def test_request_to_oneself_is_refused(client):
    async with house() as h:
        await _make_weekly(h)
        await set_module(h, "auto_adjust", True)
        resp = await _ask(client, h, target=0)
        assert resp.status_code == 400, resp.text


@pytest.mark.asyncio
async def test_request_on_another_foyers_task_is_refused(client):
    async with house() as h, house() as other:
        await _make_weekly(other)
        await set_module(h, "auto_adjust", True)
        resp = await _ask(client, h, task_id=other.task_id)
        assert resp.status_code == 404, resp.text


@pytest.mark.asyncio
async def test_revote_request_is_never_served_while_module_is_off(client):
    """Witness: why the app hides the re-vote button while auto_adjust is off."""
    async with house() as h:
        await _make_weekly(h)
        await set_module(h, "auto_adjust", False)

        resp = await _ask(client, h)
        assert resp.status_code == 200, resp.text

        served = await client.get("/api/prompts/next", headers=h.headers(1))
        assert served.status_code == 204, served.text


@pytest.mark.asyncio
async def test_chronically_late_task_asks_every_member_to_bump_effort(client):
    async with house(members=2) as h:
        await _make_weekly(h)
        await set_module(h, "auto_adjust", True)
        await _late_completions(h, late=3, neutral=1)  # 75 % late >= 70 %

        await _bump(h)

        prompts = await _bump_prompts(h)
        assert sorted(p.user_id for p in prompts) == sorted(h.user_ids)
        assert all(p.context["task_id"] == str(h.task_id) for p in prompts)


@pytest.mark.asyncio
async def test_chronically_late_task_proposes_the_module_when_it_is_off(client):
    async with house(members=2) as h:
        await _make_weekly(h)
        await set_module(h, "auto_adjust", False)
        await _late_completions(h, late=3, neutral=1)

        await _bump(h)

        assert await _bump_prompts(h) == []
        assert len(await prompts_of(h, "auto_adjust")) == 2


@pytest.mark.asyncio
async def test_task_late_only_now_and_then_asks_nothing(client):
    async with house(members=2) as h:
        await _make_weekly(h)
        await set_module(h, "auto_adjust", True)
        await _late_completions(h, late=1, neutral=3)  # 25 % late

        await _bump(h)

        assert await _bump_prompts(h) == []


async def _resolve_bump(client, h, member: int, resolution: str, action: str | None) -> None:
    mine = [p for p in await _bump_prompts(h) if p.user_id == h.user_ids[member]]
    payload = {"action": action} if action else None
    resp = await client.post(
        f"/api/prompts/{mine[-1].id}/resolve",
        json={"resolution": resolution, "payload": payload},
        headers=h.headers(member),
    )
    assert resp.status_code == 200, resp.text


async def _count_per_member(h) -> list[int]:
    prompts = await _bump_prompts(h)
    return [sum(1 for p in prompts if p.user_id == uid) for uid in h.user_ids]


@pytest.mark.asyncio
async def test_later_is_asked_again_on_the_next_late_completion_only(client):
    async with house(members=2) as h:
        await _make_weekly(h)
        await set_module(h, "auto_adjust", True)
        await _late_completions(h, late=3, neutral=1)
        await _bump(h)
        await _resolve_bump(client, h, 0, "DISMISSED", "later")
        await _resolve_bump(client, h, 1, "ANSWERED", "revote")

        await _bump(h, late=False)  # on time: nobody
        assert await _count_per_member(h) == [1, 1]

        await _bump(h, late=True)  # late: only the one who postponed
        assert await _count_per_member(h) == [2, 1]


@pytest.mark.asyncio
async def test_ignore_keeps_the_grace_period(client):
    async with house(members=2) as h:
        await _make_weekly(h)
        await set_module(h, "auto_adjust", True)
        await _late_completions(h, late=3, neutral=1)
        await _bump(h)
        await _resolve_bump(client, h, 0, "DISMISSED", "ignore")
        await _resolve_bump(client, h, 1, "DISMISSED", None)  # older client

        await _bump(h, late=True)
        assert await _count_per_member(h) == [1, 1]
