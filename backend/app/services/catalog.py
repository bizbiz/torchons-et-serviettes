"""Loader pour le catalogue de templates (v0.9.0 Feature 4).

Charge le fichier `backend/app/content/catalog.yaml` une seule fois en cache
module-level au premier appel et expose des helpers pour lookup par key.

Structure attendue du YAML :

```yaml
rooms:
  - key: cuisine
    name: { fr: "Cuisine" }
    emoji: "..."
categories:
  - key: menage
    name: { fr: "Menage" }
    color: "#..."
    requires_home: true
tasks:
  - key: vaisselle
    title: { fr: "Faire la vaisselle" }
    description: { fr: "..." }         # optionnel
    default_category_key: menage       # optionnel
    default_room_key: cuisine          # optionnel
    default_weight: 2
    default_recurrence: { ... }         # optionnel
    default_tolerance: NORMAL
```

Multi-locale : les champs label (name / title / description) sont des dicts
{locale: string}. Le helper `pick_locale` choisit l'entree de la locale
demandee, ou retombe sur 'fr' par defaut, ou sur la premiere entree dispo.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml


CATALOG_PATH = Path(__file__).parent.parent / "content" / "catalog.yaml"


@dataclass(frozen=True)
class CatalogRoom:
    key: str
    name: dict[str, str]
    emoji: str | None = None


@dataclass(frozen=True)
class CatalogCategory:
    key: str
    name: dict[str, str]
    color: str
    requires_home: bool = False


@dataclass(frozen=True)
class CatalogTask:
    key: str
    title: dict[str, str]
    description: dict[str, str] | None = None
    default_category_key: str | None = None
    default_room_key: str | None = None
    default_weight: int = 1
    default_recurrence: dict[str, Any] | None = None
    default_tolerance: str = "NORMAL"


@dataclass(frozen=True)
class Catalog:
    rooms: list[CatalogRoom] = field(default_factory=list)
    categories: list[CatalogCategory] = field(default_factory=list)
    tasks: list[CatalogTask] = field(default_factory=list)

    def room_by_key(self, key: str) -> CatalogRoom | None:
        return next((r for r in self.rooms if r.key == key), None)

    def category_by_key(self, key: str) -> CatalogCategory | None:
        return next((c for c in self.categories if c.key == key), None)

    def task_by_key(self, key: str) -> CatalogTask | None:
        return next((t for t in self.tasks if t.key == key), None)


_catalog_cache: Catalog | None = None


def _parse_catalog() -> Catalog:
    """Charge le YAML et le convertit en objets typed."""
    if not CATALOG_PATH.exists():
        return Catalog()
    with CATALOG_PATH.open("r", encoding="utf-8") as f:
        raw = yaml.safe_load(f) or {}

    rooms = [
        CatalogRoom(
            key=r["key"],
            name=r.get("name") or {},
            emoji=r.get("emoji"),
        )
        for r in raw.get("rooms", [])
    ]
    categories = [
        CatalogCategory(
            key=c["key"],
            name=c.get("name") or {},
            color=c.get("color") or "#6366F1",
            requires_home=bool(c.get("requires_home", False)),
        )
        for c in raw.get("categories", [])
    ]
    tasks = [
        CatalogTask(
            key=t["key"],
            title=t.get("title") or {},
            description=t.get("description"),
            default_category_key=t.get("default_category_key"),
            default_room_key=t.get("default_room_key"),
            default_weight=int(t.get("default_weight", 1)),
            default_recurrence=t.get("default_recurrence"),
            default_tolerance=t.get("default_tolerance", "NORMAL"),
        )
        for t in raw.get("tasks", [])
    ]
    return Catalog(rooms=rooms, categories=categories, tasks=tasks)


def get_catalog() -> Catalog:
    """Retourne le catalogue, chargeant le YAML au premier appel et cachant."""
    global _catalog_cache
    if _catalog_cache is None:
        _catalog_cache = _parse_catalog()
    return _catalog_cache


def pick_locale(labels: dict[str, str] | None, locale: str = "fr") -> str:
    """Choisit la valeur de la locale demandee, avec fallback sur 'fr' puis
    sur la premiere valeur disponible. Retourne "" si labels est vide.
    """
    if not labels:
        return ""
    if locale in labels and labels[locale]:
        return labels[locale]
    if "fr" in labels and labels["fr"]:
        return labels["fr"]
    return next(iter(labels.values()), "")
