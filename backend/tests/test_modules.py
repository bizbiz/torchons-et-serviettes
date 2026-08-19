"""Module registry — parsing, invariants and dependency rules (P1).

Split in two halves on purpose:

- the registry tests are pure Python and need no database. They are the ones
  that catch drift between modules.yaml and the schema, which is the failure
  this file exists to prevent: a `state_field` naming a column that was renamed
  would otherwise only surface in P2, in the UI, silently showing a dead toggle.
- the endpoint tests need Postgres, like the rest of the suite.
"""

from __future__ import annotations

import pytest

from app.services.modules import (
    SCOPE_COLLECTIVE,
    SCOPE_PERSONAL,
    dependents_of,
    get_registry,
    unmet_requirements,
)


# --- Registry invariants (no database) ---


def test_registry_loads_eight_modules():
    registry = get_registry()
    assert len(registry.modules) == 8, registry.keys


def test_orders_are_unique_and_contiguous():
    orders = sorted(m.order for m in get_registry().modules)
    assert orders == list(range(1, len(orders) + 1))


def test_keys_are_unique():
    keys = [m.key for m in get_registry().modules]
    assert len(keys) == len(set(keys))


def test_scopes_are_valid():
    for module in get_registry().modules:
        assert module.scope in (SCOPE_COLLECTIVE, SCOPE_PERSONAL), module.key
        for sub in module.sub_options:
            assert sub.scope in (SCOPE_COLLECTIVE, SCOPE_PERSONAL), sub.key


def test_scope_is_derived_from_nature():
    """A module holding a collective sub-option must itself be collective.

    The rule from the design: collective as soon as ONE sub-option changes the
    shared model. The converse is allowed — a collective module may carry
    personal sub-options (Motivation carries `hide_for_me`).
    """
    for module in get_registry().modules:
        has_collective_sub = any(
            s.scope == SCOPE_COLLECTIVE for s in module.sub_options
        )
        if has_collective_sub:
            assert module.is_collective, (
                f"{module.key} has a collective sub-option but is personal"
            )


def test_only_reminders_is_on_for_a_new_foyer():
    """The J0 decision: core plus Reminders, nothing else."""
    on_by_default = [m.key for m in get_registry().modules if m.default_new]
    assert on_by_default == ["reminders"]


def test_requirements_point_to_known_modules():
    keys = set(get_registry().keys)
    for module in get_registry().modules:
        for required in module.requires:
            assert required in keys, f"{module.key} requires unknown {required}"


def test_no_requirement_cycles():
    registry = get_registry()

    def walk(key: str, seen: frozenset[str]) -> None:
        assert key not in seen, f"cycle through {key}"
        module = registry.by_key(key)
        if module is None:
            return
        for required in module.requires:
            walk(required, seen | {key})

    for module in registry.modules:
        walk(module.key, frozenset())


def test_localised_copy_covers_both_locales():
    for module in get_registry().modules:
        for field_name in ("title", "pitch", "details"):
            labels = getattr(module, field_name)
            assert labels.get("fr"), f"{module.key}.{field_name} missing fr"
            assert labels.get("en"), f"{module.key}.{field_name} missing en"
        for sub in module.sub_options:
            assert sub.title.get("fr"), f"{module.key}.{sub.key} missing fr"
            assert sub.title.get("en"), f"{module.key}.{sub.key} missing en"


def test_triggers_are_declared_consistently():
    for module in get_registry().modules:
        assert module.trigger_kind in ("signal", "usage", "none"), module.key
        if module.trigger_kind == "none":
            # Only a module that is on from day one may have no trigger,
            # otherwise it could never be discovered.
            assert module.default_new, (
                f"{module.key} has no trigger and is off by default: "
                "it would be undiscoverable"
            )
        else:
            assert module.trigger_key, module.key


def test_state_fields_point_to_real_columns():
    """Every `state_field` must name a column that actually exists.

    This is the drift guard. A sub-option pointing at a renamed column would
    otherwise produce a toggle that writes nowhere.
    """
    from app.models import base  # noqa: F401  (ensures metadata is populated)
    import app.models  # noqa: F401

    tables = base.Base.metadata.tables

    for module in get_registry().modules:
        for sub in module.sub_options:
            if not sub.state_field:
                continue
            table_name, _, column_name = sub.state_field.partition(".")
            assert table_name in tables, (
                f"{module.key}.{sub.key} -> unknown table {table_name}"
            )
            assert column_name in tables[table_name].c, (
                f"{module.key}.{sub.key} -> unknown column {sub.state_field}"
            )


# --- Prompt gating (no database) ---


def test_every_prompt_type_is_mapped_to_a_module():
    """Drift guard: a new PromptType with no module would bypass the filter.

    /api/prompts/next mutes a prompt type when its module is off. A type absent
    from PROMPT_TYPE_MODULE is never muted, so it would keep interrupting a
    foyer that switched the corresponding feature off — silently, and only on
    that one type. Failing here is far cheaper than finding it in the field.

    DISCOVER_MODULE is the exception and must be declared as such: its rule is
    inverted (served when the target module is OFF). Listing it explicitly is
    what keeps "deliberately outside the normal filter" distinguishable from
    "someone forgot".
    """
    from app.models.enums import PromptType
    from app.services.modules import INVERTED_PROMPT_TYPES, PROMPT_TYPE_MODULE

    declared = {t.value for t in PromptType}
    classified = set(PROMPT_TYPE_MODULE) | INVERTED_PROMPT_TYPES
    assert declared == classified, (
        f"unclassified: {declared - classified} | unknown: {classified - declared}"
    )
    # Les deux ensembles doivent rester disjoints : un type ne peut pas etre a
    # la fois filtre normalement et inversement.
    assert not (set(PROMPT_TYPE_MODULE) & INVERTED_PROMPT_TYPES)


def test_prompt_modules_all_exist_in_the_registry():
    from app.services.modules import PROMPT_TYPE_MODULE

    keys = set(get_registry().keys)
    for prompt_type, module_key in PROMPT_TYPE_MODULE.items():
        assert module_key in keys, f"{prompt_type} -> unknown module {module_key}"


# --- Dependency rules (no database) ---


def test_bonus_malus_requires_motivation():
    bonus = get_registry().by_key("bonus_malus")
    assert bonus is not None
    assert "motivation" in bonus.requires


def test_unmet_requirements_reports_the_missing_one():
    bonus = get_registry().by_key("bonus_malus")
    assert unmet_requirements(bonus, {"motivation": False}) == ["motivation"]
    assert unmet_requirements(bonus, {"motivation": True}) == []


def test_dependents_of_motivation_includes_bonus_malus():
    assert "bonus_malus" in [m.key for m in dependents_of("motivation")]


# --- Contextual proposals, P4 (need Postgres) ---


@pytest.mark.asyncio
async def test_a_young_foyer_gets_no_proposal(client, auth_headers):
    """Nothing before day 7, whatever the signal.

    The test foyer is created by the fixture, so it is always younger than the
    threshold — which makes it the right subject for this assertion and the
    reason the later tests have to bypass `can_propose`.
    """
    import uuid as uuid_lib

    from app.database import async_session
    from app.services.module_discovery import can_propose
    from tests.conftest import TEST_FOYER_ID

    async with async_session() as session:
        allowed = await can_propose(
            session, uuid_lib.UUID(TEST_FOYER_ID), "presence"
        )
    assert allowed is False


@pytest.mark.asyncio
async def test_an_enabled_module_is_never_proposed(client, auth_headers):
    """Proposing what is already on is the most predictable bug of the lot."""
    import uuid as uuid_lib

    from app.database import async_session
    from app.services.module_discovery import can_propose
    from tests.conftest import TEST_FOYER_ID

    await client.put(
        "/api/modules/presence", headers=auth_headers, json={"enabled": True}
    )
    try:
        async with async_session() as session:
            assert await can_propose(
                session, uuid_lib.UUID(TEST_FOYER_ID), "presence"
            ) is False
    finally:
        await client.put(
            "/api/modules/presence", headers=auth_headers, json={"enabled": False}
        )


@pytest.mark.asyncio
async def test_a_proposal_is_served_then_accepting_it_enables_the_module(
    client, auth_headers
):
    """The whole loop: queued proposal -> served -> accepted -> module on.

    Written against the queue directly rather than through a signal, because
    the signals need a foyer older than a week and real history; what is under
    test here is the serving rule and the acceptance, not the trigger.
    """
    import uuid as uuid_lib

    from app.database import async_session
    from app.models.enums import PromptType
    from app.models.user_prompt import UserPrompt
    from tests.conftest import TEST_FOYER_ID, TEST_USER_ID

    await client.put(
        "/api/modules/presence", headers=auth_headers, json={"enabled": False}
    )

    prompt_id = uuid_lib.uuid4()
    async with async_session() as session:
        session.add(
            UserPrompt(
                id=prompt_id,
                user_id=uuid_lib.UUID(TEST_USER_ID),
                foyer_id=uuid_lib.UUID(TEST_FOYER_ID),
                type=PromptType.DISCOVER_MODULE,
                priority=9,
                context={"module_key": "presence", "title": "Absences"},
            )
        )
        await session.commit()

    try:
        served = await client.get("/api/prompts/next", headers=auth_headers)
        assert served.status_code == 200
        assert served.json()["id"] == str(prompt_id)
        assert served.json()["type"] == "DISCOVER_MODULE"

        accepted = await client.post(
            f"/api/prompts/{prompt_id}/resolve",
            headers=auth_headers,
            json={"resolution": "ANSWERED", "payload": {"accepted": True}},
        )
        assert accepted.status_code == 200

        modules = await client.get("/api/modules", headers=auth_headers)
        presence = next(
            m for m in modules.json()["modules"] if m["key"] == "presence"
        )
        assert presence["enabled"] is True, "accepting did not enable the module"
    finally:
        async with async_session() as session:
            row = await session.get(UserPrompt, prompt_id)
            if row is not None:
                await session.delete(row)
                await session.commit()
        await client.put(
            "/api/modules/presence", headers=auth_headers, json={"enabled": False}
        )


@pytest.mark.asyncio
async def test_a_proposal_for_an_already_enabled_module_is_not_served(
    client, auth_headers
):
    """The inverted rule, checked at READ time.

    A module can be switched on between the creation of the proposal and its
    reading — by the other member, or from Settings. The proposal is then moot,
    and serving it would ask someone to enable what they just enabled.
    """
    import uuid as uuid_lib

    from app.database import async_session
    from app.models.enums import PromptType
    from app.models.user_prompt import UserPrompt
    from tests.conftest import TEST_FOYER_ID, TEST_USER_ID

    prompt_id = uuid_lib.uuid4()
    async with async_session() as session:
        session.add(
            UserPrompt(
                id=prompt_id,
                user_id=uuid_lib.UUID(TEST_USER_ID),
                foyer_id=uuid_lib.UUID(TEST_FOYER_ID),
                type=PromptType.DISCOVER_MODULE,
                priority=9,
                context={"module_key": "presence", "title": "Absences"},
            )
        )
        await session.commit()

    await client.put(
        "/api/modules/presence", headers=auth_headers, json={"enabled": True}
    )
    try:
        resp = await client.get("/api/prompts/next", headers=auth_headers)
        served = None if resp.status_code == 204 else resp.json().get("id")
        assert served != str(prompt_id), "a moot proposal was served"
    finally:
        async with async_session() as session:
            row = await session.get(UserPrompt, prompt_id)
            if row is not None:
                await session.delete(row)
                await session.commit()
        await client.put(
            "/api/modules/presence", headers=auth_headers, json={"enabled": False}
        )


@pytest.mark.asyncio
async def test_a_silent_dismissal_does_not_burn_a_refusal(client, auth_headers):
    """A client that does not know DISCOVER_MODULE must not consume the budget.

    The shipped APK falls through to `else -> onDismiss()` on an unknown prompt
    type, which resolves it as DISMISSED without showing anything. Counting that
    would burn one of the two allowed refusals for a module nobody was ever
    offered — and two of them retire the module for good.
    """
    import uuid as uuid_lib

    from app.database import async_session
    from app.models.enums import PromptType
    from app.models.user_prompt import UserPrompt
    from app.services.module_discovery import _refusals_for
    from tests.conftest import TEST_FOYER_ID, TEST_USER_ID

    silent = uuid_lib.uuid4()
    explicit = uuid_lib.uuid4()
    async with async_session() as session:
        for pid in (silent, explicit):
            session.add(
                UserPrompt(
                    id=pid,
                    user_id=uuid_lib.UUID(TEST_USER_ID),
                    foyer_id=uuid_lib.UUID(TEST_FOYER_ID),
                    type=PromptType.DISCOVER_MODULE,
                    priority=9,
                    context={"module_key": "auto_adjust"},
                )
            )
        await session.commit()

    try:
        # Client ancien : DISMISSED sans payload.
        await client.post(
            f"/api/prompts/{silent}/resolve",
            headers=auth_headers,
            json={"resolution": "DISMISSED"},
        )
        async with async_session() as session:
            count, _ = await _refusals_for(
                session, uuid_lib.UUID(TEST_FOYER_ID), "auto_adjust"
            )
        assert count == 0, "un refus silencieux a ete compte"

        # Client a jour : DISMISSED avec le marqueur.
        await client.post(
            f"/api/prompts/{explicit}/resolve",
            headers=auth_headers,
            json={"resolution": "DISMISSED", "payload": {"declined": True}},
        )
        async with async_session() as session:
            count, _ = await _refusals_for(
                session, uuid_lib.UUID(TEST_FOYER_ID), "auto_adjust"
            )
        assert count == 1, "un refus explicite n'a pas ete compte"
    finally:
        async with async_session() as session:
            for pid in (silent, explicit):
                row = await session.get(UserPrompt, pid)
                if row is not None:
                    await session.delete(row)
            await session.commit()


@pytest.mark.asyncio
async def test_a_new_member_of_an_old_foyer_inherits_its_modules(client, auth_headers):
    """Grandfathering must survive someone joining afterwards.

    A re-paired device creates a NEW user row, so this is the common case, not
    an edge one: without the rule, one member of a foyer would see the J0
    defaults while their partner keeps everything — same foyer, two apps.
    """
    import uuid as uuid_lib

    from app.database import async_session
    from app.models.module_state import ModuleState
    from app.services.modules import resolve_states
    from tests.conftest import TEST_FOYER_ID

    foyer = uuid_lib.UUID(TEST_FOYER_ID)
    newcomer = uuid_lib.uuid4()

    async with async_session() as session:
        # Le foyer de test n'a de lignes que si un toggle a eu lieu ; on pose
        # explicitement une ligne COLLECTIVE, qui est ce qui signe un foyer
        # anterieur a la bascule.
        marker = ModuleState(
            foyer_id=foyer, user_id=None, module_key="repartition", enabled=True
        )
        session.add(marker)
        await session.commit()
        marker_id = marker.id

        states = await resolve_states(session, foyer, newcomer)

    try:
        assert states["statistics"] is True, (
            "un arrivant dans un foyer grandfathered est retombe sur les "
            "defauts J0"
        )
    finally:
        async with async_session() as session:
            row = await session.get(ModuleState, marker_id)
            if row is not None:
                await session.delete(row)
                await session.commit()


# --- Endpoints (need Postgres) ---


@pytest.mark.asyncio
async def test_get_modules_returns_the_whole_registry(client, auth_headers):
    resp = await client.get("/api/modules", headers=auth_headers)
    assert resp.status_code == 200
    payload = resp.json()["modules"]
    assert len(payload) == 8
    assert [m["order"] for m in payload] == sorted(m["order"] for m in payload)
    for module in payload:
        assert module["title"]
        assert module["pitch"]


@pytest.mark.asyncio
async def test_get_modules_localises_on_accept_language(client, auth_headers):
    fr = await client.get(
        "/api/modules", headers={**auth_headers, "Accept-Language": "fr"}
    )
    en = await client.get(
        "/api/modules", headers={**auth_headers, "Accept-Language": "en"}
    )
    assert fr.status_code == en.status_code == 200
    fr_titles = {m["key"]: m["title"] for m in fr.json()["modules"]}
    en_titles = {m["key"]: m["title"] for m in en.json()["modules"]}
    # At least one title must actually differ, otherwise localisation is a no-op.
    assert any(fr_titles[k] != en_titles[k] for k in fr_titles)


@pytest.mark.asyncio
async def test_prompts_next_mutes_a_prompt_whose_module_is_off(client, auth_headers):
    """A prompt already queued must stop being served once its module is off.

    The generators have their own guard, but it says nothing about the queue
    ALREADY built at the moment someone switches a feature off — and that
    backlog is precisely what would produce the worst experience: turning a
    feature off and still being questioned by it.
    """
    import uuid as uuid_lib

    from app.database import async_session
    from app.models.enums import PromptType
    from app.models.user_prompt import UserPrompt
    from tests.conftest import TEST_FOYER_ID, TEST_USER_ID

    # auto_adjust is off for a foyer created after the switchover.
    await client.put(
        "/api/modules/auto_adjust", headers=auth_headers, json={"enabled": False}
    )

    prompt_id = uuid_lib.uuid4()
    async with async_session() as session:
        session.add(
            UserPrompt(
                id=prompt_id,
                user_id=uuid_lib.UUID(TEST_USER_ID),
                foyer_id=uuid_lib.UUID(TEST_FOYER_ID),
                type=PromptType.BUMP_EFFORT,
                priority=9,
                context={},
            )
        )
        await session.commit()

    try:
        muted = await client.get("/api/prompts/next", headers=auth_headers)
        served = None if muted.status_code == 204 else muted.json().get("id")
        assert served != str(prompt_id), "a muted prompt was served"

        # Turning the module back on makes the same queued prompt reachable.
        await client.put(
            "/api/modules/auto_adjust", headers=auth_headers, json={"enabled": True}
        )
        again = await client.get("/api/prompts/next", headers=auth_headers)
        assert again.status_code == 200
        assert again.json()["id"] == str(prompt_id)
    finally:
        async with async_session() as session:
            row = await session.get(UserPrompt, prompt_id)
            if row is not None:
                await session.delete(row)
                await session.commit()
        await client.put(
            "/api/modules/auto_adjust", headers=auth_headers, json={"enabled": False}
        )


@pytest.mark.asyncio
async def test_unknown_module_is_404(client, auth_headers):
    resp = await client.put(
        "/api/modules/does_not_exist", headers=auth_headers, json={"enabled": True}
    )
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_toggle_round_trip(client, auth_headers):
    """Enabling then disabling `presence` leaves the registry consistent."""
    on = await client.put(
        "/api/modules/presence", headers=auth_headers, json={"enabled": True}
    )
    assert on.status_code == 200
    assert on.json()["enabled"] is True

    listed = await client.get("/api/modules", headers=auth_headers)
    presence = next(
        m for m in listed.json()["modules"] if m["key"] == "presence"
    )
    assert presence["enabled"] is True

    off = await client.put(
        "/api/modules/presence", headers=auth_headers, json={"enabled": False}
    )
    assert off.status_code == 200
    assert off.json()["enabled"] is False


@pytest.mark.asyncio
async def test_bonus_malus_refused_while_motivation_is_off(client, auth_headers):
    await client.put(
        "/api/modules/bonus_malus", headers=auth_headers, json={"enabled": False}
    )
    await client.put(
        "/api/modules/motivation", headers=auth_headers, json={"enabled": False}
    )

    resp = await client.put(
        "/api/modules/bonus_malus", headers=auth_headers, json={"enabled": True}
    )
    assert resp.status_code == 409


@pytest.mark.asyncio
async def test_motivation_cannot_be_disabled_under_an_active_bonus_malus(
    client, auth_headers
):
    await client.put(
        "/api/modules/motivation", headers=auth_headers, json={"enabled": True}
    )
    await client.put(
        "/api/modules/bonus_malus", headers=auth_headers, json={"enabled": True}
    )

    resp = await client.put(
        "/api/modules/motivation", headers=auth_headers, json={"enabled": False}
    )
    assert resp.status_code == 409

    # Restore a coherent state for the rest of the session-scoped suite.
    await client.put(
        "/api/modules/bonus_malus", headers=auth_headers, json={"enabled": False}
    )


@pytest.mark.asyncio
async def test_motivation_mirrors_the_legacy_foyer_column(client, auth_headers):
    """The shipped APK still reads foyers.gamification_enabled."""
    async def current_foyer_flag() -> bool:
        resp = await client.get("/api/foyers", headers=auth_headers)
        assert resp.status_code == 200
        current = next(f for f in resp.json() if f["is_current"])
        return current["gamification_enabled"]

    await client.put(
        "/api/modules/motivation", headers=auth_headers, json={"enabled": False}
    )
    assert await current_foyer_flag() is False

    await client.put(
        "/api/modules/motivation", headers=auth_headers, json={"enabled": True}
    )
    assert await current_foyer_flag() is True
