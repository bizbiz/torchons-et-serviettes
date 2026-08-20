"""Endpoints catalogue de templates — v0.9.0 Feature 4.

3 endpoints :
- GET /api/catalog/contexts : retourne le catalogue complet (contextes +
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
from app.models.enums import TaskTolerance
from app.models.context import Context, TaskContext
from app.models.task import Task
from app.models.task_assignment import TaskAssignment
from app.models.user_foyer import UserFoyer
from app.services.catalog import Catalog, get_catalog, pick_locale, CatalogTask
from app.utils.locale import get_request_locale
from app.utils.scoping import current_foyer_uuid

router = APIRouter(prefix="/api/catalog", tags=["catalog"])


FUZZY_THRESHOLD = 0.8


# --- Schemas ---






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


class CatalogTaskView(BaseModel):
    key: str
    title: str
    description: str | None = None
    default_weight: int
    default_recurrence: dict[str, Any] | None = None
    default_tolerance: str




class ImportResolveRequest(BaseModel):
    """Body POST /resolve : les cles de templates que l'user veut importer."""
    task_keys: list[str] = Field(default_factory=list)
    #: Nom courant. Les deux suivants sont les noms de la v0.37, acceptes tant
    #: qu'un client en circulation les emet.
    context_keys: list[str] = Field(default_factory=list)
    room_keys: list[str] = Field(default_factory=list)
    category_keys: list[str] = Field(default_factory=list)

    @property
    def chosen_context_keys(self) -> set[str]:
        """Les cles choisies, quel que soit le champ par lequel elles arrivent."""
        return set(self.context_keys) | set(self.room_keys) | set(self.category_keys)


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
    #: Nom courant. `rooms` porte la meme liste pour la v0.37, qui la lit
    #: encore ; `categories` reste vide, elle n'a plus d'objet.
    contexts: list[ResolvedItem] = Field(default_factory=list)
    rooms: list[ResolvedItem] = Field(default_factory=list)
    categories: list[ResolvedItem] = Field(default_factory=list)
    tasks: list[ResolvedItem]  # pas de conflit possible, juste pour confirmation


class ImportDecision(BaseModel):
    """Decision de l'user pour un template : soit create, soit map vers un
    item existant (existing_id fourni).
    """
    template_key: str
    decision: str  # 'create' / 'map'
    map_to_id: str | None = None


class ImportApplyRequest(BaseModel):
    task_keys: list[str] = Field(default_factory=list)
    #: Contextes explicitement choisis. `room_keys` / `category_keys` sont les
    #: noms de la v0.37 ; `*_decisions` viennent de sa reponse a /resolve.
    context_keys: list[str] = Field(default_factory=list)
    room_keys: list[str] = Field(default_factory=list)
    category_keys: list[str] = Field(default_factory=list)
    context_decisions: list[ImportDecision] = Field(default_factory=list)
    room_decisions: list[ImportDecision] = Field(default_factory=list)
    category_decisions: list[ImportDecision] = Field(default_factory=list)

    @property
    def chosen_context_keys(self) -> set[str]:
        """Les contextes retenus, quelle que soit la forme du corps recu."""
        keys = set(self.context_keys) | set(self.room_keys) | set(self.category_keys)
        for decision in (*self.context_decisions, *self.room_decisions,
                         *self.category_decisions):
            if decision.template_key:
                keys.add(decision.template_key)
        return keys


class ImportApplyResponse(BaseModel):
    created_tasks: int
    skipped: int


# --- Helpers ---


def _similar(a: str, b: str) -> float:
    return SequenceMatcher(None, a.strip().lower(), b.strip().lower()).ratio()










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







class CatalogSuggestion(BaseModel):
    """Une tache du catalogue proposee a un foyer qui ne l'a pas encore."""

    key: str
    title: str
    description: str | None = None
    #: Contextes du CATALOGUE portes par la tache, dans l'ordre.
    context_keys: list[str]
    #: Contextes du FOYER correspondants — deja resolus pour que le client
    #: puisse pre-remplir sans refaire l'appariement de son cote.
    context_ids: list[uuid.UUID]
    #: Libelle du contexte principal cote foyer, pour l'affichage groupe.
    primary_context_name: str | None = None
    primary_context_emoji: str | None = None
    default_weight: int
    default_recurrence: dict | None = None
    default_tolerance: str


class CatalogSuggestionsView(BaseModel):
    suggestions: list[CatalogSuggestion]


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



@router.get("/suggestions", response_model=CatalogSuggestionsView)
async def get_catalog_suggestions(
    limit: int = 30,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
    locale: str = Depends(get_request_locale),
):
    """Taches du catalogue pertinentes pour CE foyer, et pas deja creees.

    Pertinent = la tache touche un contexte que le foyer possede deja. Un foyer
    avec un chat se voit proposer la litiere ; un foyer sans chat ne la voit
    jamais. C'est la meme regle de visibilite que le wizard et que
    `Catalog.tasks_for` : les contextes IMPLICITES (menage, linge, entretien)
    ne rendent rien visible a eux seuls, sinon la moitie du catalogue
    remonterait pour tout le monde.

    L'appariement se fait par `template_key`, puis par nom : un contexte cree a
    la main s'appelant « Chat » doit ramener les taches de chat, sans quoi la
    fonction ne servirait qu'aux foyers issus du wizard.

    Les taches deja presentes sont exclues sur `template_key`. Une tache
    recreee a la main sous le meme titre repassera donc dans les suggestions —
    c'est le bon cote de l'erreur : mieux vaut reproposer que masquer.
    """
    catalog = get_catalog()
    foyer_id = current_foyer_uuid(current)

    contextes = (await session.execute(
        select(Context).where(
            Context.foyer_id == foyer_id, Context.deleted_at.is_(None)
        )
    )).scalars().all()
    if not contextes:
        return CatalogSuggestionsView(suggestions=[])

    # Index du foyer : par cle de template ET par nom normalise.
    par_template: dict[str, Context] = {
        c.template_key: c for c in contextes if c.template_key
    }
    par_nom: dict[str, Context] = {c.name.strip().lower(): c for c in contextes}

    def contexte_foyer(key: str) -> Context | None:
        trouve = par_template.get(key)
        if trouve is not None:
            return trouve
        tpl = catalog.context_by_key(key)
        if tpl is None:
            return None
        return par_nom.get(pick_locale(tpl.name, locale).strip().lower())

    possedes = {
        c.key for c in catalog.contexts if contexte_foyer(c.key) is not None
    }
    implicites = _implicit_keys(catalog)

    deja = {
        row for row in (await session.execute(
            select(Task.template_key).where(
                Task.foyer_id == foyer_id,
                Task.deleted_at.is_(None),
                Task.template_key.is_not(None),
            )
        )).scalars().all()
    }

    suggestions: list[CatalogSuggestion] = []
    for tpl in catalog.tasks:
        if tpl.key in deja:
            continue
        # Au moins un contexte SELECTIONNABLE possede par le foyer.
        if not any(k in possedes and k not in implicites for k in tpl.contexts):
            continue

        retenus = [
            c for c in (contexte_foyer(k) for k in tpl.contexts) if c is not None
        ]
        principal = retenus[0] if retenus else None
        suggestions.append(CatalogSuggestion(
            key=tpl.key,
            title=pick_locale(tpl.title, locale),
            description=pick_locale(tpl.description, locale) if tpl.description else None,
            context_keys=list(tpl.contexts),
            context_ids=[c.id for c in retenus],
            primary_context_name=principal.name if principal else None,
            primary_context_emoji=principal.emoji if principal else None,
            default_weight=tpl.default_weight,
            default_recurrence=tpl.default_recurrence,
            default_tolerance=tpl.default_tolerance,
        ))

    # Tri : les plus legeres d'abord. Une suggestion doit donner envie, et une
    # corvee de 45 minutes en tete de liste fait fermer l'ecran.
    suggestions.sort(key=lambda x: (x.default_weight, x.title))
    return CatalogSuggestionsView(suggestions=suggestions[:limit])


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
    # Les cles envoyees SONT des cles de contexte. Le nom du champ reste
    # `room_keys` : le renommer ne changerait rien au modele et casserait tout
    # client en vol pendant le deploiement (cf. todo.md).
    chosen = body.chosen_context_keys
    implicit = _implicit_keys(catalog)
    needed: set[str] = set(chosen)
    for tk in body.task_keys:
        task = catalog.task_by_key(tk)
        if task is not None:
            needed.update(kept_context_keys(task, chosen, implicit))

    existants = {
        c.name.strip().lower(): c
        for c in (await session.execute(
            select(Context).where(
                Context.foyer_id == foyer_id, Context.deleted_at.is_(None)
            )
        )).scalars().all()
    }

    def _resolu(key: str) -> ResolvedItem:
        tpl = catalog.context_by_key(key)
        nom = pick_locale(tpl.name, locale) if tpl else key
        deja = existants.get(nom.strip().lower())
        return ResolvedItem(
            template_key=key,
            template_name=nom,
            strategy="map" if deja is not None else "create",
            map_to_id=str(deja.id) if deja is not None else None,
        )

    contextes = [_resolu(k) for k in sorted(needed) if catalog.context_by_key(k)]
    return ImportResolveResponse(
        contexts=contextes,
        # Meme liste sous l'ancien nom : la v0.37 lit `rooms` et en fait des
        # decisions « create ». `categories` reste vide, elle n'a plus d'objet.
        rooms=contextes,
        categories=[],
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

    created_tasks = 0
    skipped = 0

    # --- Contextes ---
    # Ecriture en double avec rooms/categories : l'APK diffuse lit encore les
    # anciennes tables, le nouveau lira celles-ci. On cree un contexte par
    # template touche par l'import, en reprenant ses deux drapeaux du
    # catalogue plutot que de les deviner.
    ctx_key_to_id: dict[str, uuid.UUID] = {}
    chosen_ctx: set[str] = body.chosen_context_keys
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
        try:
            tolerance = TaskTolerance(template.default_tolerance)
        except ValueError:
            tolerance = TaskTolerance.NORMAL
        new_task = Task(
            foyer_id=foyer_id,
            title=pick_locale(template.title, locale),
            description=pick_locale(template.description, locale) if template.description else None,
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
        created_tasks=created_tasks,
        skipped=skipped,
    )
