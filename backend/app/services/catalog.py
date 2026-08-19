"""Loader du catalogue de templates — contextes (chantier contextes).

Charge `backend/app/content/catalog.yaml` une fois en cache module-level et
expose des helpers de lookup.

CE QUI A CHANGE. Les sections `rooms` et `categories` sont remplacees par une
seule section `contexts`, et une tache declare une LISTE de contextes au lieu
d'exactement une piece et exactement une categorie. Le premier declare est le
principal ; a l'affichage on retient le premier que l'utilisateur a
SELECTIONNE, pour qu'une tache listant [chat, chien] apparaisse sous Chien
chez qui n'a qu'un chien.

Structure attendue :

```yaml
contexts:
  - key: cuisine
    name: { fr: "Cuisine", en: "Kitchen" }
    color: "#F59E0B"
    emoji: "..."
    selectable: true          # propose a l'etape 3 du wizard
    default_selected: true    # pre-coche
    requires_presence: true   # faut-il etre sur place ?
    accrues_when_away: false  # le besoin monte-t-il sans nous ?
tasks:
  - key: vaisselle
    title: { fr: "...", en: "..." }
    contexts: [cuisine, menage]
    default_weight: 4
    default_recurrence: { ... }
    default_tolerance: NORMAL
    default_selected: true    # pre-coche a l'etape 4
```

Multi-locale : les champs label sont des dicts {locale: string}, resolus par
`pick_locale`.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml


CATALOG_PATH = Path(__file__).parent.parent / "content" / "catalog.yaml"


@dataclass(frozen=True)
class CatalogContext:
    key: str
    name: dict[str, str]
    color: str = "#6B7280"
    icon: str | None = None
    emoji: str | None = None
    #: Propose a l'etape 3. False = contexte implicite que tout foyer a
    #: (menage, linge...) : le demander n'aurait pas de sens.
    selectable: bool = True
    default_selected: bool = False
    requires_presence: bool = False
    accrues_when_away: bool = False

    @property
    def requires_home(self) -> bool:
        """Alias de compatibilite pour le chemin d'import de l'APK diffuse."""
        return self.requires_presence


@dataclass(frozen=True)
class CatalogTask:
    key: str
    title: dict[str, str]
    #: Ordre significatif : le premier est le contexte principal.
    contexts: tuple[str, ...] = ()
    description: dict[str, str] | None = None
    default_weight: int = 1
    default_recurrence: dict[str, Any] | None = None
    default_tolerance: str = "NORMAL"
    default_selected: bool = False

    # --- Compatibilite APK diffuse ---
    # L'ancien client ne connait qu'une piece et une categorie par tache. On
    # projette au chargement : le premier contexte implicite fait la categorie,
    # le premier selectionnable fait la piece. Champs et non methodes, pour que
    # le code d'import existant continue de fonctionner sans modification.
    # A retirer avec le reste du shim, une fois l'ancien APK hors circulation.
    default_category_key: str | None = None
    default_room_key: str | None = None


@dataclass(frozen=True)
class Catalog:
    contexts: list[CatalogContext] = field(default_factory=list)
    tasks: list[CatalogTask] = field(default_factory=list)

    # --- Compatibilite APK diffuse : rooms / categories projetes ---
    # Les contextes selectionnables passent pour des pieces, les implicites
    # pour des categories. Un chat apparait donc comme une « piece » dans
    # l'ancien client : etrange mais fonctionnel, la ou retirer ces accesseurs
    # casserait son import de templates.

    @property
    def rooms(self) -> list[CatalogContext]:
        return [c for c in self.contexts if c.selectable]

    @property
    def categories(self) -> list[CatalogContext]:
        return [c for c in self.contexts if not c.selectable]

    def room_by_key(self, key: str) -> CatalogContext | None:
        return next((c for c in self.rooms if c.key == key), None)

    def category_by_key(self, key: str) -> CatalogContext | None:
        return next((c for c in self.categories if c.key == key), None)

    def context_by_key(self, key: str) -> CatalogContext | None:
        return next((c for c in self.contexts if c.key == key), None)

    def task_by_key(self, key: str) -> CatalogTask | None:
        return next((t for t in self.tasks if t.key == key), None)

    @property
    def selectable_contexts(self) -> list[CatalogContext]:
        return [c for c in self.contexts if c.selectable]

    def tasks_for(self, selected_keys: set[str]) -> list[CatalogTask]:
        """Taches visibles pour cette selection de contextes.

        Une tache apparait si l'un de ses contextes SELECTIONNABLES est coche.
        Les contextes implicites (menage, linge...) ne rendent rien visible a
        eux seuls : sinon toute tache portant `menage` s'afficherait quoi qu'il
        arrive, ce qui reproduirait exactement le defaut qu'on corrige — 41 %
        du catalogue etait affiche sans condition avant ce chantier.
        """
        implicites = {c.key for c in self.contexts if not c.selectable}
        return [
            t for t in self.tasks
            if any(c in selected_keys for c in t.contexts if c not in implicites)
        ]

    def primary_context(self, task: CatalogTask, selected_keys: set[str]) -> str | None:
        """Sous quel contexte afficher cette tache, vu cette selection.

        Le premier contexte SELECTIONNE, et non le premier declare : une tache
        rattachee a [chat, chien] doit s'afficher sous Chien chez quelqu'un qui
        n'a qu'un chien.
        """
        for key in task.contexts:
            if key in selected_keys:
                return key
        return task.contexts[0] if task.contexts else None


_catalog_cache: Catalog | None = None


def _parse_context(raw: dict[str, Any]) -> CatalogContext:
    return CatalogContext(
        key=raw["key"],
        name=raw.get("name") or {},
        color=raw.get("color") or "#6B7280",
        icon=raw.get("icon"),
        emoji=raw.get("emoji"),
        selectable=bool(raw.get("selectable", True)),
        default_selected=bool(raw.get("default_selected", False)),
        requires_presence=bool(raw.get("requires_presence", False)),
        accrues_when_away=bool(raw.get("accrues_when_away", False)),
    )


def _parse_task(raw: dict[str, Any]) -> CatalogTask:
    return CatalogTask(
        key=raw["key"],
        title=raw.get("title") or {},
        contexts=tuple(raw.get("contexts") or ()),
        description=raw.get("description"),
        default_weight=int(raw.get("default_weight", 1)),
        default_recurrence=raw.get("default_recurrence"),
        default_tolerance=raw.get("default_tolerance", "NORMAL"),
        default_selected=bool(raw.get("default_selected", False)),
    )


def _parse_catalog() -> Catalog:
    if not CATALOG_PATH.exists():
        return Catalog()
    with CATALOG_PATH.open("r", encoding="utf-8") as f:
        raw = yaml.safe_load(f) or {}
    import dataclasses

    contexts = [_parse_context(c) for c in raw.get("contexts", [])]
    implicites = {c.key for c in contexts if not c.selectable}
    tasks = [
        dataclasses.replace(
            t,
            default_category_key=next((c for c in t.contexts if c in implicites), None),
            default_room_key=next((c for c in t.contexts if c not in implicites), None),
        )
        for t in (_parse_task(x) for x in raw.get("tasks", []))
    ]
    return Catalog(contexts=contexts, tasks=tasks)


def get_catalog() -> Catalog:
    """Retourne le catalogue, chargeant le YAML au premier appel et cachant."""
    global _catalog_cache
    if _catalog_cache is None:
        _catalog_cache = _parse_catalog()
    return _catalog_cache


def pick_locale(labels: dict[str, str] | None, locale: str = "fr") -> str:
    """Valeur de la locale demandee, repli sur 'fr' puis sur la premiere."""
    if not labels:
        return ""
    if locale in labels and labels[locale]:
        return labels[locale]
    if "fr" in labels and labels["fr"]:
        return labels["fr"]
    return next(iter(labels.values()), "")
