"""Module registry endpoints — progressive disclosure (P1).

- GET /api/modules        the registry localized, plus this member's effective
                          state for each module.
- PUT /api/modules/{key}  enable or disable one module.
- POST /api/modules/{key}/guide-seen  this member has read the module's guide.

Who may write: any member of the foyer, including for collective modules. That
is a deliberate decision (see docs/module-registry.fr.md) matching how rooms,
categories, tasks and bonus-config already behave. Traceability replaces
permission: every toggle records a change_log entry naming its author.

Dual write on `motivation`: the shipped APK still reads
`foyers.gamification_enabled`, so that column is kept in sync until the new
client is out. Dropping it before then would break live installs — see the sync
ordering rule in docs/architecture.md (Sync ordering).
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, Response
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.models.foyer import Foyer
from app.models.module_state import ModuleState
from app.services.module_guides import (
    due_module_keys,
    mark_guide_seen,
    seen_module_keys,
)
from app.services.modules import (
    Module,
    dependents_of,
    get_registry,
    resolve_states,
    unmet_requirements,
)
from app.services.catalog import pick_locale
from app.utils.changelog import record_change
from app.utils.errors import LocalizedHTTPException
from app.utils.locale import get_request_locale
from app.utils.scoping import current_foyer_uuid

router = APIRouter(prefix="/api/modules", tags=["modules"])


# --- Schemas ---


class SubOptionView(BaseModel):
    key: str
    scope: str
    title: str
    state_field: str | None = None


class ModuleView(BaseModel):
    key: str
    scope: str
    order: int
    enabled: bool
    title: str
    pitch: str
    details: str
    example: str | None = None
    requires: list[str] = []
    unmet_requires: list[str] = []
    trigger_kind: str
    sub_options: list[SubOptionView] = []
    # Guide page text (localized) and this member's reading state. `guide_due`:
    # show it at the next app open (see services/module_guides.py).
    guide_why: str = ""
    guide_how: str = ""
    guide_seen: bool = False
    guide_due: bool = False


class ModulesResponse(BaseModel):
    modules: list[ModuleView]


class ModuleToggleRequest(BaseModel):
    enabled: bool


# --- Helpers ---


def _titles(keys: list[str], locale: str) -> str:
    """Module keys rendered as their localized titles, for user-facing errors.

    The registry key is an internal identifier; putting `bonus_malus` in front
    of someone who was shown "Bonus and penalty" makes them look for a setting
    that does not exist under that name.
    """
    registry = get_registry()
    return ", ".join(
        pick_locale(m.title, locale) if (m := registry.by_key(k)) else k
        for k in keys
    )


def _to_view(
    module: Module,
    enabled: bool,
    states: dict[str, bool],
    locale: str,
    seen: set[str] = frozenset(),
    due: set[str] = frozenset(),
) -> ModuleView:
    return ModuleView(
        key=module.key,
        scope=module.scope,
        order=module.order,
        enabled=enabled,
        title=pick_locale(module.title, locale),
        pitch=pick_locale(module.pitch, locale),
        details=pick_locale(module.details, locale),
        example=pick_locale(module.example, locale) if module.example else None,
        requires=list(module.requires),
        unmet_requires=unmet_requirements(module, states),
        trigger_kind=module.trigger_kind,
        guide_why=pick_locale(module.guide_why, locale),
        guide_how=pick_locale(module.guide_how, locale),
        guide_seen=module.key in seen,
        guide_due=module.key in due,
        sub_options=[
            SubOptionView(
                key=s.key,
                scope=s.scope,
                title=pick_locale(s.title, locale),
                state_field=s.state_field,
            )
            for s in module.sub_options
        ],
    )


# --- Endpoints ---


@router.get("", response_model=ModulesResponse)
async def list_modules(
    current: dict = Depends(get_current_user),
    locale: str = Depends(get_request_locale),
    session: AsyncSession = Depends(get_session),
) -> ModulesResponse:
    """Registry plus the caller's effective state for each module."""
    foyer_id = current_foyer_uuid(current)
    user_id = uuid.UUID(current["user_id"])

    states = await resolve_states(session, foyer_id, user_id)
    registry = get_registry()
    seen = await seen_module_keys(session, user_id)
    due = await due_module_keys(session, foyer_id, user_id, states, seen)

    return ModulesResponse(
        modules=[
            _to_view(m, states[m.key], states, locale, seen, due)
            for m in registry.modules
        ]
    )


@router.post("/{module_key}/guide-seen", status_code=204)
async def mark_module_guide_seen(
    module_key: str,
    current: dict = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> Response:
    """Record that this member has read the module's guide. Idempotent.

    Per USER, not per foyer: no foyer is read here. Replaying it, or sending it
    for a module the member never enabled, is harmless.
    """
    if get_registry().by_key(module_key) is None:
        raise LocalizedHTTPException(status_code=404, error_code="MODULE_UNKNOWN")
    await mark_guide_seen(session, uuid.UUID(current["user_id"]), module_key)
    await session.commit()
    return Response(status_code=204)


@router.put("/{module_key}", response_model=ModuleView)
async def toggle_module(
    module_key: str,
    body: ModuleToggleRequest,
    current: dict = Depends(get_current_user),
    locale: str = Depends(get_request_locale),
    session: AsyncSession = Depends(get_session),
) -> ModuleView:
    """Enable or disable one module for this foyer (collective) or member."""
    foyer_id = current_foyer_uuid(current)
    user_id = uuid.UUID(current["user_id"])

    module = get_registry().by_key(module_key)
    if module is None:
        raise LocalizedHTTPException(status_code=404, error_code="MODULE_UNKNOWN")

    states = await resolve_states(session, foyer_id, user_id)

    # Dependency guard, both directions. Bonus/malus without Motivation has
    # nothing to modulate, so neither order of operations may produce it.
    if body.enabled:
        unmet = unmet_requirements(module, states)
        if unmet:
            raise LocalizedHTTPException(
                status_code=409,
                error_code="MODULE_REQUIRES",
                format_args={"requires": _titles(unmet, locale)},
            )
    else:
        active_dependents = [
            d.key for d in dependents_of(module.key) if states.get(d.key, False)
        ]
        if active_dependents:
            raise LocalizedHTTPException(
                status_code=409,
                error_code="MODULE_HAS_DEPENDENTS",
                format_args={"dependents": _titles(active_dependents, locale)},
            )

    # Collective rows carry a NULL user_id; personal ones carry the member's.
    row_user_id = None if module.is_collective else user_id

    existing = await session.execute(
        select(ModuleState).where(
            ModuleState.foyer_id == foyer_id,
            ModuleState.module_key == module.key,
            ModuleState.user_id.is_(None)
            if row_user_id is None
            else ModuleState.user_id == row_user_id,
        )
    )
    state = existing.scalar_one_or_none()
    was_enabled = state.enabled if state is not None else module.default_new
    now = datetime.now(timezone.utc)

    if state is None:
        state = ModuleState(
            foyer_id=foyer_id,
            user_id=row_user_id,
            module_key=module.key,
            enabled=body.enabled,
            enabled_at=now if body.enabled else None,
            updated_by_user_id=user_id,
        )
        session.add(state)
    else:
        state.enabled = body.enabled
        state.updated_by_user_id = user_id
        # Only stamp on a real off -> on transition, so re-confirming an already
        # enabled module does not rewrite its activation date.
        if body.enabled and not was_enabled:
            state.enabled_at = now

    # Legacy mirror for the shipped client. Removed once the new APK is out.
    if module.key == "motivation":
        foyer = await session.get(Foyer, foyer_id)
        if foyer is not None:
            foyer.gamification_enabled = body.enabled

    await session.flush()

    await record_change(
        session,
        table_name="module_states",
        row_id=state.id,
        operation="update",
        changed_by=str(user_id),
        foyer_id=foyer_id,
        old_data={"module_key": module.key, "enabled": was_enabled},
        new_data={"module_key": module.key, "enabled": body.enabled},
    )
    await session.commit()

    states[module.key] = body.enabled
    # `due` stays empty: the actor is the one who just switched it, never "another member".
    seen = await seen_module_keys(session, user_id)
    return _to_view(module, body.enabled, states, locale, seen)
