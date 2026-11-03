"""Usage-threshold proposals (app/services/module_usage_triggers.py).

Invariant: a module is proposed to a foyer when its threshold is met, never one
step before, never to a member who already has it, never twice within the
foyer's 5-day floor, and never more than one per pass.

PATHS by which a proposal can be created or wrongly withheld (callers of
`propose_module`, and the conditions in `USAGE_TRIGGERS`):
  (1) motivation, 20 live completions, dead ones excluded ........... COVERED
  (2) statistics, oldest live completion 30 days old ................ COVERED
  (3) objectives, last 3 daily snapshots each >= 3 overdue .......... COVERED
  (4) bonus_malus, motivation on for 14 days ........................ COVERED
  (5) 5-day floor between two proposals ............................. COVERED
  (6) stop at the first trigger that proposed ....................... COVERED
  (7) rotation: an ignored module does not starve the next .......... COVERED
  (8) personal module (statistics): members who have it are skipped . COVERED
  (9) the daily job: archived foyers skipped, one failure isolated .. COVERED
  (10) the guide text carried by the prompt ......................... COVERED
Not covered: the APScheduler registration at 03:45 UTC (checked by hand against
`lifespan`, which starts a real scheduler); the signal call sites (pairing,
presence, prompts), unchanged and covered in test_modules.py.

Every threshold test sets its inputs ABSOLUTELY (19 vs 20, 29 vs 31 days...) and
not relative to the constant, so that moving a constant turns one of them red.
Margins to the boundary are a full unit or more: the hour the suite runs never
decides an outcome.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import update

from tests.module_helpers import (
    add_completions,
    add_snapshots,
    house,
    prompts_of,
    run_triggers,
    set_module,
)


async def _backdate_prompts(h, module_key: str, days_ago: float) -> None:
    from app.database import async_session
    from app.models.user_prompt import UserPrompt

    rows = await prompts_of(h, module_key)
    assert rows, f"nothing to backdate for {module_key}"
    async with async_session() as session:
        await session.execute(
            update(UserPrompt)
            .where(UserPrompt.id.in_([r.id for r in rows]))
            .values(created_at=datetime.now(timezone.utc) - timedelta(days=days_ago))
        )
        await session.commit()


# --- Thresholds ---


@pytest.mark.asyncio
async def test_motivation_needs_twenty_live_completions():
    async with house() as h:
        await add_completions(h, 19, deleted=6)
        assert await run_triggers(h) is None, "19 live completions proposed it"
        assert await prompts_of(h) == []

        await add_completions(h, 1)
        assert await run_triggers(h) == "motivation"
        sent = await prompts_of(h, "motivation")
        assert len(sent) == 2, "one prompt per active member"
        assert sent[0].context["reason"] == "completions_reached"


@pytest.mark.asyncio
async def test_statistics_needs_a_thirty_day_old_completion():
    async with house() as h:
        await add_completions(h, 3, oldest_days_ago=29)
        assert await run_triggers(h) is None, "a 29-day history proposed it"

    async with house() as h:
        await add_completions(h, 3, oldest_days_ago=31)
        assert await run_triggers(h) == "statistics"


@pytest.mark.asyncio
async def test_a_deleted_old_completion_does_not_age_the_history():
    async with house() as h:
        await add_completions(h, 3, oldest_days_ago=2)
        # The dead rows are the only old ones: 40 days ago, soft-deleted.
        from app.database import async_session
        from app.models.completion import TaskCompletion

        async with async_session() as session:
            session.add(
                TaskCompletion(
                    task_id=h.task_id,
                    completed_by=h.user_ids[0],
                    completed_at=datetime.now(timezone.utc) - timedelta(days=40),
                    deleted_at=datetime.now(timezone.utc),
                )
            )
            await session.commit()
        assert await run_triggers(h) is None


@pytest.mark.asyncio
async def test_objectives_needs_three_snapshots_each_at_three_overdue():
    async with house() as h:
        await add_snapshots(h, [3, 3, 2])
        assert await run_triggers(h) is None, "a snapshot at 2 overdue passed"

    async with house() as h:
        await add_snapshots(h, [2, 3, 3])
        assert await run_triggers(h) is None, "the OLDEST snapshot at 2 passed"

    async with house() as h:
        await add_snapshots(h, [3, 3])
        assert await run_triggers(h) is None, "two snapshots passed"

    async with house() as h:
        # Only the last three count: the older low one is out of the window.
        await add_snapshots(h, [1, 3, 4, 3])
        assert await run_triggers(h) == "objectives"
        assert (await prompts_of(h, "objectives"))[0].context["reason"] == (
            "backlog_threshold_reached"
        )


@pytest.mark.asyncio
async def test_bonus_malus_needs_motivation_on_for_fourteen_days():
    async with house() as h:
        await set_module(h, "motivation", True, enabled_days_ago=13)
        assert await run_triggers(h) is None, "motivation on for 13 days proposed it"

    async with house() as h:
        await set_module(h, "motivation", True, enabled_days_ago=15)
        assert await run_triggers(h) == "bonus_malus"

    async with house() as h:
        # Motivation off: no state to build on, whatever the history says. The
        # row keeps the `enabled_at` of the time it was on, as the API leaves it.
        await set_module(h, "motivation", False, enabled_days_ago=20)
        assert await run_triggers(h) is None


# --- Budget and ordering ---


@pytest.mark.asyncio
async def test_two_proposals_in_a_foyer_are_five_days_apart_at_least():
    async with house() as h:
        # Motivation and statistics are both met.
        await add_completions(h, 20, oldest_days_ago=31)
        assert await run_triggers(h) == "motivation"

        # Motivation is now rotated out; statistics is held by the floor alone.
        assert await run_triggers(h) is None, "second proposal the same day"
        await _backdate_prompts(h, "motivation", 4)
        assert await run_triggers(h) is None, "second proposal after 4 days"
        await _backdate_prompts(h, "motivation", 6)
        assert await run_triggers(h) == "statistics"


@pytest.mark.asyncio
async def test_a_pass_stops_at_the_first_proposal():
    async with house() as h:
        await add_completions(h, 20, oldest_days_ago=31)
        await add_snapshots(h, [4, 4, 4])
        assert await run_triggers(h) == "motivation", "registry order not respected"
        assert await prompts_of(h, "statistics") == []
        assert await prompts_of(h, "objectives") == []


@pytest.mark.asyncio
async def test_an_ignored_module_does_not_starve_the_next_one():
    async with house() as h:
        await add_completions(h, 20, oldest_days_ago=31)
        assert await run_triggers(h) == "motivation"
        # Ignored for 6 days: the slot is free again but motivation sits out.
        await _backdate_prompts(h, "motivation", 6)
        assert await run_triggers(h) == "statistics"

    async with house() as h:
        await add_completions(h, 20, oldest_days_ago=31)
        assert await run_triggers(h) == "motivation"
        # Ignored for 31 days: its silence is over and it comes first again.
        await _backdate_prompts(h, "motivation", 31)
        assert await run_triggers(h) == "motivation"


@pytest.mark.asyncio
async def test_a_young_foyer_is_proposed_nothing():
    async with house(age_days=3) as h:
        await add_completions(h, 25, oldest_days_ago=31)
        assert await run_triggers(h) is None


@pytest.mark.asyncio
async def test_an_enabled_module_is_not_proposed_by_its_trigger():
    async with house() as h:
        await add_completions(h, 25)
        await set_module(h, "motivation", True)
        assert await run_triggers(h) is None


# --- The personal module ---


@pytest.mark.asyncio
async def test_statistics_skips_a_member_who_already_has_it():
    """`is_module_enabled` without a user cannot see a personal module's state."""
    async with house() as h:
        await add_completions(h, 3, oldest_days_ago=31)
        # Member 0 has statistics on, member 1 has no row: a foyer without any
        # collective row is not grandfathered, so the default (off) applies.
        await set_module(h, "statistics", True, user=0)
        assert await run_triggers(h) == "statistics"
        sent = await prompts_of(h, "statistics")
        assert [p.user_id for p in sent] == [h.user_ids[1]]


@pytest.mark.asyncio
async def test_statistics_is_not_proposed_when_every_member_has_it():
    async with house() as h:
        await add_completions(h, 3, oldest_days_ago=31)
        await set_module(h, "statistics", True, user=0)
        await set_module(h, "statistics", True, user=1)
        assert await run_triggers(h) is None


@pytest.mark.asyncio
async def test_statistics_is_proposed_to_a_member_who_switched_it_off():
    async with house() as h:
        await add_completions(h, 3, oldest_days_ago=31)
        await set_module(h, "statistics", True, user=0)
        await set_module(h, "statistics", False, user=1)
        assert await run_triggers(h) == "statistics"
        sent = await prompts_of(h, "statistics")
        assert [p.user_id for p in sent] == [h.user_ids[1]]


# --- Guide text on the prompt ---


@pytest.mark.asyncio
async def test_the_proposal_carries_the_guide_text():
    from app.services.modules import get_registry

    async with house() as h:
        await add_completions(h, 20)
        assert await run_triggers(h) == "motivation"
        ctx = (await prompts_of(h, "motivation"))[0].context
    module = get_registry().by_key("motivation")
    assert ctx["guide_why"] == module.guide_why["fr"]
    assert ctx["guide_how"] == module.guide_how["fr"]
    assert ctx["guide_why"] and ctx["guide_how"]


# --- The daily job ---


@pytest.mark.asyncio
async def test_the_job_skips_archived_foyers_and_isolates_failures(monkeypatch):
    from app.database import async_session
    from app.models.foyer import Foyer
    from app.services import module_usage_triggers as mut

    async with house() as live, house() as archived, house() as broken:
        for h in (live, archived, broken):
            await add_completions(h, 20)
        async with async_session() as session:
            row = await session.get(Foyer, archived.foyer_id)
            row.archived_at = datetime.now(timezone.utc)
            await session.commit()

        mine = {live.foyer_id, archived.foyer_id, broken.foyer_id}
        real = mut.evaluate_usage_triggers
        seen: list = []

        async def only_mine(session, foyer_id):
            # The database holds other foyers (the fixture one): leave them alone.
            if foyer_id not in mine:
                return None
            seen.append(foyer_id)
            if foyer_id == broken.foyer_id:
                raise RuntimeError("boom")
            return await real(session, foyer_id)

        monkeypatch.setattr(mut, "evaluate_usage_triggers", only_mine)
        await mut.daily_usage_triggers_job()

        assert archived.foyer_id not in seen, "an archived foyer was evaluated"
        assert broken.foyer_id in seen and live.foyer_id in seen
        assert len(await prompts_of(live, "motivation")) == 2, (
            "a failing foyer stopped the others"
        )
        assert await prompts_of(archived) == []
        assert await prompts_of(broken) == []
