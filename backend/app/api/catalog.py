"""Endpoints catalogue de templates — v0.9.0 Feature 4.

3 endpoints :
- GET /api/catalog : retourne le catalogue complet (rooms + categories +
  tasks), en resolvant les labels multi-locale vers la locale de l'user
  (fr en v0.9.0, extensible plus tard).
- POST /api/catalog/import/resolve : l'user a selectionne des tasks a
  importer. Le backend analyse les rooms/categories referencees par ces
  tasks + celles selectionnees directement, et pour chacune retourne la
  strategie d'import suggeree : "create" (pas d'equivalent trouve),
  "map_existing" (match par template_key ou fuzzy ratio > 0.8 sur le nom)
  avec l'id de l'item existant, ou "skip_rattachement" (cas edge).
- POST /api/catalog/import/apply : l'user a valide ses decisions dans le
  modal de mapping. Backend applique : cree les rooms/categories manquantes,
  cree les tasks, set les template_key. Retourne un recap.

Fuzzy matching : on utilise difflib.SequenceMatcher.ratio() > 0.8 sur les
noms en lower+strip. Suffisant pour detecter "Cuisine" vs "cuisine " ou
"Menage" vs "Menages".
"""

from __future__ import annotations

import uuid
from difflib import SequenceMatcher
from typing import Any

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.models.category import Category
from app.models.enums import TaskTolerance
from app.models.room import Room
from app.models.task import Task
from app.models.task_assignment import TaskAssignment
from app.models.user_foyer import UserFoyer
from app.services.catalog import Catalog, get_catalog, pick_locale
from app.utils.scoping import current_foyer_uuid

router = APIRouter(prefix="/api/catalog", tags=["catalog"])


FUZZY_THRESHOLD = 0.8


# --- Schemas ---


class CatalogRoomView(BaseModel):
    key: str
    name: str
    emoji: str | None = None


class CatalogCategoryView(BaseModel):
    key: str
    name: str
    color: str
    requires_home: bool


class CatalogTaskView(BaseModel):
    key: str
    title: str
    description: str | None = None
    default_category_key: str | None = None
    default_room_key: str | None = None
    default_weight: int
    default_recurrence: dict[str, Any] | None = None
    default_tolerance: str


class CatalogView(BaseModel):
    rooms: list[CatalogRoomView]
    categories: list[CatalogCategoryView]
    tasks: list[CatalogTaskView]


class ImportResolveRequest(BaseModel):
    """Body POST /resolve : les keys de templates que l'user veut importer."""
    task_keys: list[str] = Field(default_factory=list)
    room_keys: list[str] = Field(default_factory=list)
    category_keys: list[str] = Field(default_factory=list)


class ResolvedItem(BaseModel):
    """Une resolution par template_key. Represente l'etat initial du choix
    dans le modal de mapping.
    """
    template_key: str
    template_name: str  # label localise pour l'affichage dans le modal
    strategy: str  # 'create' / 'map_existing' / 'already_same_template'
    existing_id: str | None = None  # si map_existing, id de l'item existant
    existing_name: str | None = None  # si map_existing ou already_same_template


class ImportResolveResponse(BaseModel):
    rooms: list[ResolvedItem]
    categories: list[ResolvedItem]
    tasks: list[ResolvedItem]  # tasks n'ont pas de resolution (pas de conflit), juste pour confirmation


class ImportDecision(BaseModel):
    """Decision de l'user pour un template : soit create, soit map vers un
    item existant (existing_id fourni).
    """
    template_key: str
    decision: str  # 'create' / 'map'
    map_to_id: str | None = None


class ImportApplyRequest(BaseModel):
    task_keys: list[str] = Field(default_factory=list)
    room_decisions: list[ImportDecision] = Field(default_factory=list)
    category_decisions: list[ImportDecision] = Field(default_factory=list)
    # Rooms/categories selectionnees explicitement (pas juste referencees)
    room_keys: list[str] = Field(default_factory=list)
    category_keys: list[str] = Field(default_factory=list)


class ImportApplyResponse(BaseModel):
    created_rooms: int
    created_categories: int
    created_tasks: int
    skipped: int


# --- Helpers ---


def _similar(a: str, b: str) -> float:
    return SequenceMatcher(None, a.strip().lower(), b.strip().lower()).ratio()


async def _existing_rooms(session: AsyncSession, foyer_id: uuid.UUID) -> list[Room]:
    result = await session.execute(
        select(Room).where(
            Room.foyer_id == foyer_id,
            Room.deleted_at.is_(None),
        )
    )
    return list(result.scalars().all())


async def _existing_categories(session: AsyncSession, foyer_id: uuid.UUID) -> list[Category]:
    result = await session.execute(
        select(Category).where(
            Category.foyer_id == foyer_id,
            Category.deleted_at.is_(None),
        )
    )
    return list(result.scalars().all())


def _resolve_room(
    room_key: str, catalog: Catalog, existing: list[Room]
) -> ResolvedItem:
    template = catalog.room_by_key(room_key)
    if template is None:
        return ResolvedItem(
            template_key=room_key, template_name=room_key, strategy="create"
        )
    template_name = pick_locale(template.name)
    # Deja rattachee a ce template ?
    by_key = next((r for r in existing if r.template_key == room_key), None)
    if by_key is not None:
        return ResolvedItem(
            template_key=room_key,
            template_name=template_name,
            strategy="already_same_template",
            existing_id=str(by_key.id),
            existing_name=by_key.name,
        )
    # Fuzzy match par nom
    best = None
    best_ratio = 0.0
    for r in existing:
        ratio = _similar(r.name, template_name)
        if ratio > best_ratio:
            best_ratio = ratio
            best = r
    if best is not None and best_ratio >= FUZZY_THRESHOLD:
        return ResolvedItem(
            template_key=room_key,
            template_name=template_name,
            strategy="map_existing",
            existing_id=str(best.id),
            existing_name=best.name,
        )
    return ResolvedItem(
        template_key=room_key, template_name=template_name, strategy="create"
    )


def _resolve_category(
    cat_key: str, catalog: Catalog, existing: list[Category]
) -> ResolvedItem:
    template = catalog.category_by_key(cat_key)
    if template is None:
        return ResolvedItem(
            template_key=cat_key, template_name=cat_key, strategy="create"
        )
    template_name = pick_locale(template.name)
    by_key = next((c for c in existing if c.template_key == cat_key), None)
    if by_key is not None:
        return ResolvedItem(
            template_key=cat_key,
            template_name=template_name,
            strategy="already_same_template",
            existing_id=str(by_key.id),
            existing_name=by_key.name,
        )
    best = None
    best_ratio = 0.0
    for c in existing:
        ratio = _similar(c.name, template_name)
        if ratio > best_ratio:
            best_ratio = ratio
            best = c
    if best is not None and best_ratio >= FUZZY_THRESHOLD:
        return ResolvedItem(
            template_key=cat_key,
            template_name=template_name,
            strategy="map_existing",
            existing_id=str(best.id),
            existing_name=best.name,
        )
    return ResolvedItem(
        template_key=cat_key, template_name=template_name, strategy="create"
    )


# --- Endpoints ---


@router.get("", response_model=CatalogView)
async def get_catalog_endpoint(
    current: dict = Depends(get_current_user),
):
    """Retourne le catalogue curate (rooms + categories + tasks) en locale fr."""
    catalog = get_catalog()
    return CatalogView(
        rooms=[
            CatalogRoomView(
                key=r.key,
                name=pick_locale(r.name),
                emoji=r.emoji,
            )
            for r in catalog.rooms
        ],
        categories=[
            CatalogCategoryView(
                key=c.key,
                name=pick_locale(c.name),
                color=c.color,
                requires_home=c.requires_home,
            )
            for c in catalog.categories
        ],
        tasks=[
            CatalogTaskView(
                key=t.key,
                title=pick_locale(t.title),
                description=pick_locale(t.description) if t.description else None,
                default_category_key=t.default_category_key,
                default_room_key=t.default_room_key,
                default_weight=t.default_weight,
                default_recurrence=t.default_recurrence,
                default_tolerance=t.default_tolerance,
            )
            for t in catalog.tasks
        ],
    )


@router.post("/import/resolve", response_model=ImportResolveResponse)
async def resolve_import(
    body: ImportResolveRequest,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Analyse les items a importer et retourne les propositions de mapping.

    Les tasks selectionnees referencent des rooms/categories (via
    default_room_key / default_category_key) qu'il faut potentiellement
    creer ou mapper. On ajoute aussi les rooms/categories selectionnees
    explicitement (room_keys / category_keys) pour le meme traitement.
    """
    catalog = get_catalog()
    foyer_id = current_foyer_uuid(current)

    # Collect rooms/categories necessaires : selectionnees + referencees par tasks
    needed_room_keys: set[str] = set(body.room_keys)
    needed_cat_keys: set[str] = set(body.category_keys)
    for tk in body.task_keys:
        task = catalog.task_by_key(tk)
        if task is None:
            continue
        if task.default_room_key:
            needed_room_keys.add(task.default_room_key)
        if task.default_category_key:
            needed_cat_keys.add(task.default_category_key)

    existing_rooms = await _existing_rooms(session, foyer_id)
    existing_cats = await _existing_categories(session, foyer_id)

    return ImportResolveResponse(
        rooms=[_resolve_room(k, catalog, existing_rooms) for k in sorted(needed_room_keys)],
        categories=[_resolve_category(k, catalog, existing_cats) for k in sorted(needed_cat_keys)],
        tasks=[
            ResolvedItem(
                template_key=tk,
                template_name=pick_locale(catalog.task_by_key(tk).title) if catalog.task_by_key(tk) else tk,
                strategy="create",
            )
            for tk in body.task_keys
            if catalog.task_by_key(tk) is not None
        ],
    )


@router.post("/import/apply", response_model=ImportApplyResponse)
async def apply_import(
    body: ImportApplyRequest,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Execute l'import apres validation du modal de mapping par l'user.

    Pour chaque decision :
    - 'create' : cree la room/category dans le foyer actif + set template_key
    - 'map' : reutilise l'item existant map_to_id

    Pour chaque task_key selectionnee : cree la task, assigne sa room/category
    en fonction du mapping, set template_key + assigne l'user courant.
    """
    catalog = get_catalog()
    user_id = uuid.UUID(current["user_id"])
    foyer_id = current_foyer_uuid(current)

    created_rooms = 0
    created_categories = 0
    created_tasks = 0
    skipped = 0

    existing_rooms = await _existing_rooms(session, foyer_id)
    existing_cats = await _existing_categories(session, foyer_id)

    # Resolve final room_key -> id (existing ou nouvellement cree)
    room_key_to_id: dict[str, uuid.UUID] = {}
    for decision in body.room_decisions:
        template = catalog.room_by_key(decision.template_key)
        if template is None:
            continue
        if decision.decision == "map" and decision.map_to_id:
            room_key_to_id[decision.template_key] = uuid.UUID(decision.map_to_id)
        elif decision.decision == "create":
            # Eviter de re-creer si une row existe deja avec ce template_key
            existing_by_key = next(
                (r for r in existing_rooms if r.template_key == decision.template_key),
                None,
            )
            if existing_by_key is not None:
                room_key_to_id[decision.template_key] = existing_by_key.id
                continue
            name = pick_locale(template.name)
            # Anti-collision d'unicite : si un nom identique existe deja, on
            # le reutilise au lieu de crasher l'uniqueness.
            existing_by_name = next(
                (r for r in existing_rooms if r.name == name), None
            )
            if existing_by_name is not None:
                room_key_to_id[decision.template_key] = existing_by_name.id
                continue
            new_room = Room(
                foyer_id=foyer_id,
                name=name,
                emoji=template.emoji,
                template_key=decision.template_key,
            )
            session.add(new_room)
            await session.flush()
            room_key_to_id[decision.template_key] = new_room.id
            created_rooms += 1

    # Resolve final category_key -> id
    cat_key_to_id: dict[str, uuid.UUID] = {}
    for decision in body.category_decisions:
        template = catalog.category_by_key(decision.template_key)
        if template is None:
            continue
        if decision.decision == "map" and decision.map_to_id:
            cat_key_to_id[decision.template_key] = uuid.UUID(decision.map_to_id)
        elif decision.decision == "create":
            existing_by_key = next(
                (c for c in existing_cats if c.template_key == decision.template_key),
                None,
            )
            if existing_by_key is not None:
                cat_key_to_id[decision.template_key] = existing_by_key.id
                continue
            name = pick_locale(template.name)
            existing_by_name = next(
                (c for c in existing_cats if c.name == name), None
            )
            if existing_by_name is not None:
                cat_key_to_id[decision.template_key] = existing_by_name.id
                continue
            new_cat = Category(
                foyer_id=foyer_id,
                name=name,
                color=template.color,
                requires_home=template.requires_home,
                template_key=decision.template_key,
            )
            session.add(new_cat)
            await session.flush()
            cat_key_to_id[decision.template_key] = new_cat.id
            created_categories += 1

    # Enfin : les tasks
    for tk in body.task_keys:
        template = catalog.task_by_key(tk)
        if template is None:
            skipped += 1
            continue
        room_id = None
        if template.default_room_key:
            room_id = room_key_to_id.get(template.default_room_key)
        cat_id = None
        if template.default_category_key:
            cat_id = cat_key_to_id.get(template.default_category_key)
        try:
            tolerance = TaskTolerance(template.default_tolerance)
        except ValueError:
            tolerance = TaskTolerance.NORMAL
        new_task = Task(
            foyer_id=foyer_id,
            title=pick_locale(template.title),
            description=pick_locale(template.description) if template.description else None,
            category_id=cat_id,
            room_id=room_id,
            recurrence_rule=template.default_recurrence,
            weight=template.default_weight,
            tolerance=tolerance,
            is_active=True,
            template_key=template.key,
        )
        session.add(new_task)
        await session.flush()
        # Assigne l'user courant par defaut (comportement du CRUD existant)
        session.add(TaskAssignment(task_id=new_task.id, user_id=user_id))
        created_tasks += 1

    await session.commit()
    return ImportApplyResponse(
        created_rooms=created_rooms,
        created_categories=created_categories,
        created_tasks=created_tasks,
        skipped=skipped,
    )
