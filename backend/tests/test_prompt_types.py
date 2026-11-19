"""Prompt type classification: which module (if any) gates each PromptType.

Moved out of test_modules.py (size budget). The drift guard needs no database.

VOTE_RATING is a historical value: its two generators were removed (nothing called them,
production held no such prompt since April) and rating renewal is served by
GET /api/pending-affinity. The value stays in the PromptType enum, classified as core.
"""

from __future__ import annotations

from app.models.enums import PromptType
from app.services.modules import (
    CORE_PROMPT_TYPES,
    INVERTED_PROMPT_TYPES,
    PROMPT_TYPE_MODULE,
    get_registry,
)


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
    - CORE_PROMPT_TYPES: owned by no module, never muted (VOTE_RATING, a
      historical value that is never generated any more).
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
