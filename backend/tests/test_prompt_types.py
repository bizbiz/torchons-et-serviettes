"""Prompt type classification: which module (if any) gates each PromptType.

Moved out of test_modules.py (size budget). The drift guard needs no database;
the VOTE_RATING tests need Postgres like the rest of the suite.

Paths by which "VOTE_RATING is not gated by a module" can be violated:
- generate_vote_prompts_for_task (creation path): covered, repartition off.
- generate_expired_vote_prompts (nightly path): not covered, needs expired
  rating rows; it lost the same gate in the same edit.
- GET /api/prompts/next (read filter): covered, via PROMPT_TYPE_MODULE no longer
  listing the type; the drift guard covers re-adding it.
- Non-covered: no production caller of either generator exists today (grep
  of backend/app), so the API-level creation route cannot be exercised.
"""

from __future__ import annotations

import pytest
from sqlalchemy import select

from app.models.enums import PromptType
from app.services.modules import (
    CORE_PROMPT_TYPES,
    INVERTED_PROMPT_TYPES,
    PROMPT_TYPE_MODULE,
    get_registry,
)
from tests.module_helpers import house, set_module


def test_every_prompt_type_is_mapped_to_a_module():
    """Drift guard: a new PromptType with no module would bypass the filter.

    /api/prompts/next mutes a prompt type when its module is off. A type absent
    from PROMPT_TYPE_MODULE is never muted, so it would keep interrupting a
    foyer that switched the corresponding feature off, silently, and only on
    that one type. Failing here is far cheaper than finding it in the field.

    Two kinds of type are deliberately outside the normal filter and must be
    declared as such, which keeps them distinguishable from "someone forgot":
    - INVERTED_PROMPT_TYPES: served when the target module is OFF
      (DISCOVER_MODULE);
    - CORE_PROMPT_TYPES: owned by no module, never muted (VOTE_RATING).
    """
    declared = {t.value for t in PromptType}
    classified = set(PROMPT_TYPE_MODULE) | INVERTED_PROMPT_TYPES | CORE_PROMPT_TYPES
    assert declared == classified, (
        f"unclassified: {declared - classified} | unknown: {classified - declared}"
    )
    # The three sets must stay pairwise disjoint: a type is filtered normally,
    # inverted, or core, never two at once.
    assert not (set(PROMPT_TYPE_MODULE) & INVERTED_PROMPT_TYPES)
    assert not (set(PROMPT_TYPE_MODULE) & CORE_PROMPT_TYPES)
    assert not (INVERTED_PROMPT_TYPES & CORE_PROMPT_TYPES)


def test_prompt_modules_all_exist_in_the_registry():
    keys = set(get_registry().keys)
    for prompt_type, module_key in PROMPT_TYPE_MODULE.items():
        assert module_key in keys, f"{prompt_type} -> unknown module {module_key}"


@pytest.mark.asyncio
async def test_vote_rating_is_generated_and_served_with_repartition_off(client):
    """Affinity and effort ratings are core: no module switches the question off."""
    from app.database import async_session
    from app.models.task import Task
    from app.models.user_prompt import UserPrompt
    from app.services.prompts import generate_vote_prompts_for_task

    async with house() as h:
        await set_module(h, "repartition", False)

        async with async_session() as session:
            task = await session.get(Task, h.task_id)
            task.recurrence_rule = {"type": "interval", "every_n_days": 7}
            await generate_vote_prompts_for_task(
                session, task, h.foyer_id, [h.user_ids[0]]
            )
            await session.commit()

        async with async_session() as session:
            rows = (
                await session.execute(
                    select(UserPrompt).where(
                        UserPrompt.foyer_id == h.foyer_id,
                        UserPrompt.type == PromptType.VOTE_RATING,
                    )
                )
            ).scalars().all()
        assert [r.user_id for r in rows] == [h.user_ids[0]]

        served = await client.get("/api/prompts/next", headers=h.headers(0))
        assert served.status_code == 200, served.text
        assert served.json()["type"] == "VOTE_RATING"
