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
from app.models.context import Context, TaskContext
from app.models.room import Room
from app.models.task import Task
from app.models.task_assignment import TaskAssignment
from app.models.user_foyer import UserFoyer
from app.services.catalog import Catalog, get_catalog, pick_locale, CatalogTask
from app.utils.locale import get_request_locale
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
    room_key: str, catalog: Catalog, existing: list[Room], locale: str = "fr"
) -> ResolvedItem:
    template = catalog.room_by_key(room_key)
    if template is None:
        return ResolvedItem(
            template_key=room_key, template_name=room_key, strategy="create"
        )
    template_name = pick_locale(template.name, locale)
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
    cat_key: str, catalog: Catalog, existing: list[Category], locale: str = "fr"
) -> ResolvedItem:
    template = catalog.category_by_key(cat_key)
    if template is None:
        return ResolvedItem(
            template_key=cat_key, template_name=cat_key, strategy="create"
        )
    template_name = pick_locale(template.name, locale)
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



def _implicit_keys(catalog: Catalog) -> set[str]:
    """Contextes que tout foyer a (menage, linge, entretien) : jamais demandes."""
    return {c.key for c in catalog.contexts if not c.selectable}


def kept_context_keys(
    task: CatalogTask, chosen: set[str], implicit: set[str]
) -> list[str]:
    """Contextes a rattacher a cette tache, dans l'ordre du catalogue.

    Une tache en declare souvent plus que le foyer n'en possede : « Faire les
    comptes communs » porte [colocation, administratif], et un foyer d'une
    personne ne doit pas heriter d'un contexte Colocation qu'il n'a jamais
    coche. On ne garde donc que ce que l'utilisateur a choisi, plus les
    implicites.

    Le repli sur le premier declare ne sert qu'a ne jamais laisser une tache
    orpheline : sans contexte, elle sortirait de tous les filtres.
    """
    kept = [k for k in task.contexts if k in implicit or k in chosen]
    return kept or list(task.contexts[:1])


def legacy_room_key(catalog: Catalog, kept: list[str]) -> str | None:
    """Piece heritee correspondante : le premier contexte selectionnable retenu.

    Compatibilite avec l'APK diffuse, qui lit encore `tasks.room_id`. A retirer
    avec le reste du shim.
    """
    for k in kept:
        ctx = catalog.context_by_key(k)
        if ctx is not None and ctx.selectable:
            return k
    return None


def legacy_category_key(catalog: Catalog, kept: list[str]) -> str | None:
    """Categorie heritee correspondante : le premier contexte implicite retenu."""
    for k in kept:
        ctx = catalog.context_by_key(k)
        if ctx is not None and not ctx.selectable:
            return k
    return None


@router.get("", response_model=CatalogView)
async def get_catalog_endpoint(
    locale: str = Depends(get_request_locale),
):
    """Retourne le catalogue curate (rooms + categories + tasks) dans la
    locale demandee (Accept-Language header, fallback "fr").

    **Public (pas d'auth)** : ce sont des donnees template globales, non
    sensibles, identiques pour tout le monde. Et surtout le wizard
    "Creer un foyer" sans compte doit lire le catalogue AVANT la creation
    du foyer (donc avant l'emission du JWT). Exiger l'auth ici cassait
    l'onboarding public (etape Pieces en chargement infini sur 401) —
    bug remonte par la beta dogfood Scaleway le 2026-05-15. Les endpoints
    /import/* restent authentifies (ils ecrivent dans un foyer)."""
    # COMPATIBILITE — cette reponse garde la forme rooms/categories que l'APK
    # diffuse attend. Le catalogue ne connait plus que des contextes : on les
    # projette, les selectionnables passant pour des pieces et les implicites
    # pour des categories. Un chat apparait donc comme une « piece » dans
    # l'ancien client : c'est etrange mais fonctionnel, la ou changer la forme
    # casserait son wizard (ecran Pieces en chargement infini).
    #
    # Le nouveau client lit /api/catalog/contexts. A retirer une fois l'ancien
    # APK hors circulation.
    catalog = get_catalog()
    implicites = {c.key for c in catalog.contexts if not c.selectable}
    return CatalogView(
        rooms=[
            CatalogRoomView(key=c.key, name=pick_locale(c.name, locale), emoji=c.emoji)
            for c in catalog.contexts if c.selectable
        ],
        categories=[
            CatalogCategoryView(
                key=c.key,
                name=pick_locale(c.name, locale),
                color=c.color,
                requires_home=c.requires_presence,
            )
            for c in catalog.contexts if not c.selectable
        ],
        tasks=[
            CatalogTaskView(
                key=t.key,
                title=pick_locale(t.title, locale),
                description=pick_locale(t.description, locale) if t.description else None,
                # Le premier contexte implicite fait office de categorie, le
                # premier selectionnable de piece — l'ancien client n'en
                # accepte qu'un de chaque.
                default_category_key=t.default_category_key,
                default_room_key=t.default_room_key,
                default_weight=t.default_weight,
                default_recurrence=t.default_recurrence,
                default_tolerance=t.default_tolerance,
            )
            for t in catalog.tasks
        ],
    )


class CatalogContextView(BaseModel):
    key: str
    name: str
    color: str
    emoji: str | None = None
    #: False = contexte implicite (menage, linge...). Jamais propose a
    #: l'etape 3 : « faites-vous du menage ? » n'a pas de sens.
    selectable: bool
    default_selected: bool
    requires_presence: bool
    accrues_when_away: bool


class CatalogTaskContextView(BaseModel):
    key: str
    title: str
    description: str | None = None
    #: Ordre significatif : le premier declare est le principal.
    contexts: list[str]
    default_weight: int
    default_recurrence: dict | None = None
    default_tolerance: str
    default_selected: bool


class CatalogContextsView(BaseModel):
    contexts: list[CatalogContextView]
    tasks: list[CatalogTaskContextView]


@router.get("/contexts", response_model=CatalogContextsView)
async def get_catalog_contexts(
    locale: str = Depends(get_request_locale),
):
    """Catalogue sous sa forme contextes — ce que lit le wizard refondu.

    **Public (pas d'auth)**, pour la meme raison que `GET /api/catalog` : le
    wizard « Creer un foyer » lit le catalogue AVANT la creation du foyer,
    donc avant l'emission du JWT. Exiger l'auth ici remettrait l'etape en
    chargement infini sur 401, bug deja paye le 2026-05-15.

    Le client n'a pas a filtrer : il recoit tout, et decide quoi montrer selon
    les contextes coches. La regle de visibilite (une tache apparait si l'un de
    ses contextes SELECTIONNABLES est coche) est documentee dans
    `Catalog.tasks_for` cote serveur et rejouee a l'identique cote client.
    """
    catalog = get_catalog()
    return CatalogContextsView(
        contexts=[
            CatalogContextView(
                key=c.key,
                name=pick_locale(c.name, locale),
                color=c.color,
                emoji=c.emoji,
                selectable=c.selectable,
                default_selected=c.default_selected,
                requires_presence=c.requires_presence,
                accrues_when_away=c.accrues_when_away,
            )
            for c in catalog.contexts
        ],
        tasks=[
            CatalogTaskContextView(
                key=t.key,
                title=pick_locale(t.title, locale),
                description=pick_locale(t.description, locale) if t.description else None,
                contexts=list(t.contexts),
                default_weight=t.default_weight,
                default_recurrence=t.default_recurrence,
                default_tolerance=t.default_tolerance,
                default_selected=t.default_selected,
            )
            for t in catalog.tasks
        ],
    )


@router.post("/import/resolve", response_model=ImportResolveResponse)
async def resolve_import(
    body: ImportResolveRequest,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
    locale: str = Depends(get_request_locale),
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
    chosen = set(body.room_keys) | set(body.category_keys)
    implicit = _implicit_keys(catalog)
    for tk in body.task_keys:
        task = catalog.task_by_key(tk)
        if task is None:
            continue
        # La piece deduite de la tache n'est proposee que si elle fait partie
        # des contextes coches : sinon le client en ferait une decision
        # « create » et l'import recreerait un contexte jamais demande.
        kept = kept_context_keys(task, chosen, implicit)
        room_key = legacy_room_key(catalog, kept)
        if room_key:
            needed_room_keys.add(room_key)
        cat_key = legacy_category_key(catalog, kept)
        if cat_key:
            needed_cat_keys.add(cat_key)

    existing_rooms = await _existing_rooms(session, foyer_id)
    existing_cats = await _existing_categories(session, foyer_id)

    return ImportResolveResponse(
        rooms=[_resolve_room(k, catalog, existing_rooms, locale) for k in sorted(needed_room_keys)],
        categories=[_resolve_category(k, catalog, existing_cats, locale) for k in sorted(needed_cat_keys)],
        tasks=[
            ResolvedItem(
                template_key=tk,
                template_name=pick_locale(catalog.task_by_key(tk).title, locale) if catalog.task_by_key(tk) else tk,
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
    locale: str = Depends(get_request_locale),
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
            name = pick_locale(template.name, locale)
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
            # Dé-dup INTRA-boucle : sans ça, deux template_keys au même
            # nom localisé créeraient 2 rows -> viol. uq_rooms_name_foyer
            # -> IntegrityError 500 (bug add-foyer : un user existant
            # importe un catalogue à noms collidants dans un 2e foyer).
            existing_rooms.append(new_room)

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
            name = pick_locale(template.name, locale)
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
            # Dé-dup INTRA-boucle (cf rooms) : évite la 2e création au
            # même nom -> viol. uq_categories_name_foyer -> 500.
            existing_cats.append(new_cat)

    # --- Contextes ---
    # Ecriture en double avec rooms/categories : l'APK diffuse lit encore les
    # anciennes tables, le nouveau lira celles-ci. On cree un contexte par
    # template touche par l'import, en reprenant ses deux drapeaux du
    # catalogue plutot que de les deviner.
    ctx_key_to_id: dict[str, uuid.UUID] = {}
    chosen_ctx: set[str] = set(body.room_keys) | set(body.category_keys)
    chosen_ctx.update(d.template_key for d in body.room_decisions if d.template_key)
    chosen_ctx.update(d.template_key for d in body.category_decisions if d.template_key)
    implicit_ctx = _implicit_keys(catalog)

    def kept_contexts(tpl: CatalogTask) -> list[str]:
        return kept_context_keys(tpl, chosen_ctx, implicit_ctx)

    touched_ctx: set[str] = set(chosen_ctx)
    for tk in body.task_keys:
        tpl = catalog.task_by_key(tk)
        if tpl is not None:
            touched_ctx.update(kept_contexts(tpl))

    existing_ctx = (await session.execute(
        select(Context).where(Context.foyer_id == foyer_id)
    )).scalars().all()
    by_name = {c.name.strip().lower(): c for c in existing_ctx}
    by_template = {c.template_key: c for c in existing_ctx if c.template_key}

    for key in sorted(touched_ctx):
        tpl_ctx = catalog.context_by_key(key)
        if tpl_ctx is None:
            continue
        name = pick_locale(tpl_ctx.name, locale)
        # Reutiliser un contexte deja present, par template puis par nom : un
        # second import ne doit pas dupliquer « Cuisine ».
        found = by_template.get(key) or by_name.get(name.strip().lower())
        if found is not None:
            ctx_key_to_id[key] = found.id
            continue
        new_ctx = Context(
            foyer_id=foyer_id,
            name=name,
            color=tpl_ctx.color,
            emoji=tpl_ctx.emoji,
            requires_presence=tpl_ctx.requires_presence,
            accrues_when_away=tpl_ctx.accrues_when_away,
            template_key=key,
        )
        session.add(new_ctx)
        await session.flush()
        ctx_key_to_id[key] = new_ctx.id
        by_name[name.strip().lower()] = new_ctx
        by_template[key] = new_ctx

    # Enfin : les tasks
    for tk in body.task_keys:
        template = catalog.task_by_key(tk)
        if template is None:
            skipped += 1
            continue
        kept = kept_contexts(template)
        # Piece/categorie heritees : deduites du rattachement RETENU et non du
        # catalogue brut, sinon elles pointeraient vers un contexte ecarte.
        room_key = legacy_room_key(catalog, kept)
        room_id = room_key_to_id.get(room_key) if room_key else None
        cat_key = legacy_category_key(catalog, kept)
        cat_id = cat_key_to_id.get(cat_key) if cat_key else None
        try:
            tolerance = TaskTolerance(template.default_tolerance)
        except ValueError:
            tolerance = TaskTolerance.NORMAL
        new_task = Task(
            foyer_id=foyer_id,
            title=pick_locale(template.title, locale),
            description=pick_locale(template.description, locale) if template.description else None,
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

        # Rattachements : l'ordre du catalogue est conserve dans `position`,
        # c'est lui qui designe le contexte principal.
        for position, ctx_key in enumerate(kept):
            ctx_id = ctx_key_to_id.get(ctx_key)
            if ctx_id is not None:
                session.add(TaskContext(
                    task_id=new_task.id, context_id=ctx_id, position=position,
                ))
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
