"""Module registry loader and state resolution (P1).

Loads `backend/app/content/modules.yaml` once into a module-level cache, and
resolves the effective enablement of each module for a (foyer, user) pair.

Resolution order for one module:
  1. an explicit row in `module_states` for the right scope, if any;
  2. otherwise `default_new` from the registry.

Rule 2 is what makes grandfathering work without a flag: the backfill migration
writes explicit rows for every foyer that existed at switchover time, so falling
through to `default_new` can only happen for a foyer created afterwards.

See docs/module-registry.fr.md for the decisions this implements.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import uuid
import yaml
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.module_state import ModuleState


MODULES_PATH = Path(__file__).parent.parent / "content" / "modules.yaml"

SCOPE_COLLECTIVE = "collective"
SCOPE_PERSONAL = "personal"


@dataclass(frozen=True)
class SubOption:
    key: str
    scope: str
    title: dict[str, str]
    # Dotted path to the table.column already holding this sub-option's state,
    # when one exists. Informational for now: P2 wires the settings screen from
    # it instead of inventing new storage.
    state_field: str | None = None


@dataclass(frozen=True)
class Module:
    key: str
    scope: str
    default_new: bool
    order: int
    title: dict[str, str]
    pitch: dict[str, str]
    details: dict[str, str]
    example: dict[str, str] | None = None
    #: Localized "why" and "how" of the module's guide page (modules.yaml `guide`).
    guide_why: dict[str, str] = field(default_factory=dict)
    guide_how: dict[str, str] = field(default_factory=dict)
    requires: tuple[str, ...] = ()
    trigger_kind: str = "none"
    trigger_key: str | None = None
    sub_options: tuple[SubOption, ...] = ()

    @property
    def is_collective(self) -> bool:
        return self.scope == SCOPE_COLLECTIVE


@dataclass(frozen=True)
class ModuleRegistry:
    modules: tuple[Module, ...] = field(default_factory=tuple)

    def by_key(self, key: str) -> Module | None:
        return next((m for m in self.modules if m.key == key), None)

    @property
    def keys(self) -> tuple[str, ...]:
        return tuple(m.key for m in self.modules)


_registry_cache: ModuleRegistry | None = None


def _parse_sub_option(raw: dict[str, Any]) -> SubOption:
    return SubOption(
        key=raw["key"],
        scope=raw.get("scope") or SCOPE_COLLECTIVE,
        title=raw.get("title") or {},
        state_field=raw.get("state_field"),
    )


def _parse_module(raw: dict[str, Any]) -> Module:
    trigger = raw.get("trigger") or {}
    guide = raw.get("guide") or {}
    return Module(
        key=raw["key"],
        scope=raw.get("scope") or SCOPE_COLLECTIVE,
        default_new=bool(raw.get("default_new", False)),
        order=int(raw.get("order", 0)),
        title=raw.get("title") or {},
        pitch=raw.get("pitch") or {},
        details=raw.get("details") or {},
        example=raw.get("example"),
        guide_why=guide.get("why") or {},
        guide_how=guide.get("how") or {},
        requires=tuple(raw.get("requires") or ()),
        trigger_kind=trigger.get("kind") or "none",
        trigger_key=trigger.get("key"),
        sub_options=tuple(
            _parse_sub_option(s) for s in (raw.get("sub_options") or [])
        ),
    )


def _parse_registry() -> ModuleRegistry:
    if not MODULES_PATH.exists():
        return ModuleRegistry()
    with MODULES_PATH.open("r", encoding="utf-8") as f:
        raw = yaml.safe_load(f) or {}
    modules = [_parse_module(m) for m in raw.get("modules", [])]
    modules.sort(key=lambda m: m.order)
    return ModuleRegistry(modules=tuple(modules))


def get_registry() -> ModuleRegistry:
    """Return the registry, parsing the YAML on first call and caching it."""
    global _registry_cache
    if _registry_cache is None:
        _registry_cache = _parse_registry()
    return _registry_cache


async def resolve_states(
    session: AsyncSession,
    foyer_id: uuid.UUID,
    user_id: uuid.UUID,
) -> dict[str, bool]:
    """Effective enablement of every module for this member in this foyer.

    Collective modules read the foyer-wide row (user_id IS NULL); personal ones
    read this member's row. Either falls back to `default_new` when absent.
    """
    registry = get_registry()

    result = await session.execute(
        select(ModuleState).where(
            ModuleState.foyer_id == foyer_id,
            (ModuleState.user_id.is_(None)) | (ModuleState.user_id == user_id),
        )
    )
    rows = result.scalars().all()

    collective = {r.module_key: r.enabled for r in rows if r.user_id is None}
    personal = {r.module_key: r.enabled for r in rows if r.user_id is not None}

    # Un foyer qui porte des lignes COLLECTIVES a ete grandfathered par la
    # migration : il existait avant la bascule. Un membre arrive depuis n'a pas
    # de lignes PERSO — et un appareil re-appaire cree un nouvel utilisateur,
    # donc ce cas est courant, pas marginal. Lui appliquer les defauts J0 lui
    # donnerait un ecran different de celui de son conjoint dans le meme foyer.
    #
    # The backfill is recognised by its rows being UNSIGNED (updated_by_user_id
    # NULL): both writers since then (PUT /modules, accepting a module proposal)
    # sign theirs. Until 2026-11-04 any collective row counted, so a NEW foyer
    # switching on its first collective module also switched on every member's
    # personal modules (seen on the beta: Statistics appeared with Auto-adjust).
    # Measured on prod the same day: all 19 foyers keep unsigned collective rows.
    # Edge left open: a row whose author is deleted is set NULL (ondelete).
    grandfathered = any(r.user_id is None and r.updated_by_user_id is None for r in rows)

    states: dict[str, bool] = {}
    for module in registry.modules:
        if module.is_collective:
            states[module.key] = collective.get(module.key, module.default_new)
        else:
            fallback = True if grandfathered else module.default_new
            states[module.key] = personal.get(module.key, fallback)
    return states


async def is_module_enabled(
    session: AsyncSession,
    foyer_id: uuid.UUID,
    key: str,
    user_id: uuid.UUID | None = None,
) -> bool:
    """Effective enablement of one module, without loading the whole registry state.

    `user_id` is only needed for personal modules; for collective ones the
    foyer-wide row (user_id IS NULL) is authoritative and the argument is
    ignored. Falls back to `default_new` when no row exists — same rule as
    `resolve_states`, so a foyer created after the switchover is treated as
    new here too.

    Used by the prompt generators: decision 1 of the design says whatever
    INTERRUPTS is not generated at all while its module is off, as opposed to
    whatever accumulates, which keeps running silently.
    """
    module = get_registry().by_key(key)
    if module is None:
        return False

    conditions = [ModuleState.foyer_id == foyer_id, ModuleState.module_key == key]
    if module.is_collective or user_id is None:
        conditions.append(ModuleState.user_id.is_(None))
    else:
        conditions.append(ModuleState.user_id == user_id)

    result = await session.execute(select(ModuleState.enabled).where(*conditions))
    row = result.scalar_one_or_none()
    if row is not None:
        return row

    # Meme regle de repli que `resolve_states` : dans un foyer grandfathered,
    # un module perso sans ligne vaut actif.
    if not module.is_collective:
        has_collective = await session.scalar(
            select(ModuleState.id)
            .where(
                ModuleState.foyer_id == foyer_id,
                ModuleState.user_id.is_(None),
                # Unsigned = written by the backfill (see resolve_states).
                ModuleState.updated_by_user_id.is_(None),
            )
            .limit(1)
        )
        if has_collective is not None:
            return True
    return module.default_new


# Which module owns each prompt type. A prompt whose module is off must be
# neither generated nor served: the queue is the interruption channel, and an
# interruption from a feature the foyer switched off is exactly what the whole
# design exists to prevent.
#: Types dont la regle est INVERSEE : servis quand le module vise est ETEINT.
#: Un seul aujourd'hui, mais la liste doit exister pour que le garde
#: anti-derive puisse distinguer "type non classe" (bug) de "type volontairement
#: hors du filtre normal" (DISCOVER_MODULE).
INVERTED_PROMPT_TYPES: set[str] = {"DISCOVER_MODULE"}


PROMPT_TYPE_MODULE: dict[str, str] = {
    "BUMP_EFFORT": "auto_adjust",
    "VOTE_FREQUENCY": "auto_adjust",
    "VOTE_EFFORT": "auto_adjust",
    "VOTE_RATING": "repartition",
    "RESOLVE_PRESENCE": "presence",
}


def unmet_requirements(
    module: Module, states: dict[str, bool]
) -> list[str]:
    """Required modules that are not enabled. Empty means the module can be on."""
    return [k for k in module.requires if not states.get(k, False)]


def dependents_of(key: str) -> list[Module]:
    """Modules that declare `key` as a requirement.

    Used when disabling: switching Motivation off must not leave Bonus/malus
    enabled with nothing to modulate.
    """
    return [m for m in get_registry().modules if key in m.requires]
