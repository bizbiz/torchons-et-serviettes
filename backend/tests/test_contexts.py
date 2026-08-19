"""Import du catalogue sur les contextes (chantier contextes).

Ce qui est verifie ici n'est pas que l'import « marche », mais qu'il produit
les rattachements MULTIPLES et leur ORDRE : c'est tout l'apport du chantier, et
c'est exactement ce qu'un import qui retomberait sur une seule piece par tache
rendrait silencieusement faux.
"""

from __future__ import annotations

import uuid as uuid_lib

import pytest
from sqlalchemy import delete, select

from app.api.catalog import (
    _implicit_keys,
    kept_context_keys,
    legacy_category_key,
    legacy_room_key,
)
from app.models.context import Context, TaskContext
from app.services.catalog import get_catalog


def test_catalog_exposes_contexts_not_rooms():
    c = get_catalog()
    assert len(c.contexts) > 30
    assert any(x.key == "chat" for x in c.contexts), "les animaux manquent"
    assert any(x.key == "jardin" for x in c.contexts), "l'exterieur manque"


def test_implicit_contexts_alone_reveal_nothing():
    """Le defaut central corrige par ce chantier.

    Avant, 41 % du catalogue n'avait aucun rattachement et s'affichait donc
    quoi qu'il arrive — y compris les taches de chat a qui n'a pas de chat.
    Un contexte implicite ne doit rendre AUCUNE tache visible a lui seul.
    """
    c = get_catalog()
    implicites = {x.key for x in c.contexts if not x.selectable}
    assert implicites, "aucun contexte implicite : le jeu de test ne prouve rien"
    assert c.tasks_for(implicites) == []


def test_every_task_has_at_least_one_selectable_context():
    c = get_catalog()
    implicites = {x.key for x in c.contexts if not x.selectable}
    orphelines = [
        t.key for t in c.tasks
        if not [x for x in t.contexts if x not in implicites]
    ]
    assert orphelines == [], f"invisibles a vie : {orphelines[:5]}"


def test_display_context_follows_the_user_selection():
    """Une tache [chat, chien] s'affiche sous Chien chez qui n'a qu'un chien."""
    c = get_catalog()
    partagee = next(
        (t for t in c.tasks if "chat" in t.contexts and "chien" in t.contexts), None
    )
    assert partagee is not None, "aucune tache partagee chat/chien dans le catalogue"
    assert c.primary_context(partagee, {"chien"}) == "chien"
    assert c.primary_context(partagee, {"chat"}) == "chat"


@pytest.mark.asyncio
async def test_import_creates_contexts_and_ordered_links(client, auth_headers):
    """Bout en bout : l'import cree les contextes ET les rattachements ordonnes."""
    from app.database import async_session
    from app.models.context import Context, TaskContext
    from app.models.task import Task
    from tests.conftest import TEST_FOYER_ID

    catalog = get_catalog()
    # Une tache multi-contextes, pour que l'assertion sur l'ordre ait un objet.
    multi = next(t for t in catalog.tasks if len(t.contexts) >= 2)

    # La suite tourne sur la base de DEV, sur un foyer de test qui n'est pas
    # purge entre les runs : sans ce nettoyage, l'assertion lit la tache d'un
    # run precedent et rend un verdict sur du code qui n'a pas tourne.
    async with async_session() as session:
        anciennes = (await session.execute(
            select(Task).where(
                Task.foyer_id == uuid_lib.UUID(TEST_FOYER_ID),
                Task.template_key == multi.key,
            )
        )).scalars().all()
        for vieille in anciennes:
            await session.execute(
                delete(TaskContext).where(TaskContext.task_id == vieille.id)
            )
            await session.delete(vieille)
        await session.commit()

    # Les contextes selectionnables sont envoyes comme le fait le client : sans
    # eux, l'import ne rattache que les implicites — c'est la regle voulue, pas
    # un rattachement manquant.
    implicites = _implicit_keys(catalog)
    choisis = [k for k in multi.contexts if k not in implicites]

    resp = await client.post(
        "/api/catalog/import/apply",
        headers=auth_headers,
        json={"task_keys": [multi.key], "room_keys": choisis},
    )
    assert resp.status_code in (200, 201), resp.text

    foyer = uuid_lib.UUID(TEST_FOYER_ID)
    async with async_session() as session:
        task = (await session.execute(
            select(Task).where(Task.foyer_id == foyer, Task.template_key == multi.key)
        )).scalars().first()
        assert task is not None, "la tache n'a pas ete creee"

        liens = (await session.execute(
            select(TaskContext).where(TaskContext.task_id == task.id)
            .order_by(TaskContext.position)
        )).scalars().all()
        noms = []
        for lien in liens:
            ctx = await session.get(Context, lien.context_id)
            noms.append(ctx.template_key)
        assert len(liens) == len(multi.contexts), f"rattachements manquants : {noms}"
        assert noms == list(multi.contexts), "l'ordre du catalogue n'est pas conserve"

        # Les drapeaux viennent du catalogue, pas d'un defaut.
        premier = await session.get(Context, liens[0].context_id)
        attendu = catalog.context_by_key(multi.contexts[0])
        assert premier.requires_presence == attendu.requires_presence
        assert premier.accrues_when_away == attendu.accrues_when_away


@pytest.mark.asyncio
async def test_a_second_import_does_not_duplicate_contexts(client, auth_headers):
    """Reimporter ne doit pas creer un second « Cuisine »."""
    from app.database import async_session
    from app.models.context import Context
    from tests.conftest import TEST_FOYER_ID

    catalog = get_catalog()
    multi = next(t for t in catalog.tasks if len(t.contexts) >= 2)
    foyer = uuid_lib.UUID(TEST_FOYER_ID)

    async def compter() -> int:
        async with async_session() as session:
            rows = (await session.execute(
                select(Context).where(Context.foyer_id == foyer)
            )).scalars().all()
            return len(rows)

    avant = await compter()
    await client.post(
        "/api/catalog/import/apply",
        headers=auth_headers,
        json={"task_keys": [multi.key]},
    )
    assert await compter() == avant, "l'import a duplique des contextes"


def test_an_unchosen_context_is_not_dragged_in_by_a_task():
    """Une tache ne doit pas faire entrer un contexte que l'user n'a pas coche.

    « Faire les comptes communs » declare [colocation, administratif]. Un foyer
    d'une personne qui coche Administratif obtenait aussi un contexte
    Colocation — mesure sur un foyer de test : 11 contextes crees pour 7
    coches, dont Bureau et Colocation jamais demandes.

    On appelle la fonction REELLE de l'import et non une copie de sa regle :
    la premiere version de ce test reimplementait le filtre, et serait restee
    verte pendant que le serveur, lui, continuait a creer Colocation.
    """
    catalog = get_catalog()
    implicites = _implicit_keys(catalog)
    choisis = {"administratif"}

    comptes = catalog.task_by_key("faire_les_comptes_communs_loyer_charges_pot_comm")
    assert comptes is not None, "la tache temoin a disparu du catalogue"
    assert "colocation" in comptes.contexts, "le cas teste n'existe plus"

    retenus = kept_context_keys(comptes, choisis, implicites)
    assert "colocation" not in retenus
    assert "administratif" in retenus
    # La piece heritee suit le rattachement retenu, elle ne retombe pas sur
    # le premier contexte declare du catalogue.
    assert legacy_room_key(catalog, retenus) == "administratif"


def test_a_task_is_never_left_without_a_context():
    """Le repli existe pour ca : sans contexte, une tache sort de tout filtre."""
    catalog = get_catalog()
    implicites = _implicit_keys(catalog)
    orphelines = [
        t.key for t in catalog.tasks
        if not kept_context_keys(t, set(), implicites)
    ]
    assert orphelines == [], f"taches sans aucun contexte : {orphelines[:5]}"


def test_the_shipped_apk_still_gets_its_room_and_category():
    """Garde-fou de compatibilite : l'APK diffuse lit `room_id`/`category_id`.

    Il envoie des cles de PIECE dans `room_keys` et laisse le serveur deduire
    la categorie. La regle de restriction ne doit pas lui retirer la piece
    qu'il vient justement de demander : pour chaque tache visible sous une
    piece choisie, la piece et la categorie heritees doivent rester celles du
    catalogue.
    """
    catalog = get_catalog()
    implicites = _implicit_keys(catalog)

    for piece in ("cuisine", "salle_de_bain", "jardin", "chat"):
        choisis = {piece}
        for tache in catalog.tasks_for(choisis):
            retenus = kept_context_keys(tache, choisis, implicites)
            # La piece choisie est bien celle qui sera ecrite dans room_id.
            assert legacy_room_key(catalog, retenus) == piece, (
                f"{tache.key} sous {piece} perdrait sa piece"
            )
            # La categorie heritee reste celle du catalogue quand il y en a une.
            attendue = tache.default_category_key
            assert legacy_category_key(catalog, retenus) == attendue, (
                f"{tache.key} changerait de categorie"
            )


@pytest.mark.asyncio
async def test_context_crud_and_legacy_twin(client, auth_headers):
    """Le CRUD ecrit aussi la piece jumelle, sans quoi l'APK diffuse perd le lieu.

    C'est le point que le shim existe pour tenir : tant que l'ancien client lit
    `rooms`, un contexte cree depuis le nouveau n'existerait pas chez un membre
    du foyer reste sur l'ancien.
    """
    from app.database import async_session
    from app.models.room import Room
    from tests.conftest import TEST_FOYER_ID

    nom = "Cabane de test"
    foyer = uuid_lib.UUID(TEST_FOYER_ID)

    # Etat de depart propre. La base de dev n'est pas purgee entre les runs, et
    # le nettoyage doit couvrir le nom d'arrivee du renommage : ne purger que
    # le nom de depart laissait un « … bis » vivant qui faisait echouer le run
    # suivant en 409, sur un code pourtant correct.
    async with async_session() as session:
        for modele in (Context, Room):
            for ligne in (await session.execute(
                select(modele).where(
                    modele.foyer_id == foyer, modele.name.like(nom + "%")
                )
            )).scalars().all():
                if modele is Context:
                    await session.execute(
                        delete(TaskContext).where(TaskContext.context_id == ligne.id)
                    )
                await session.delete(ligne)
        await session.commit()

    resp = await client.post(
        "/api/contexts", headers=auth_headers,
        json={"name": nom, "emoji": "🛖", "accrues_when_away": True},
    )
    assert resp.status_code == 201, resp.text
    cree = resp.json()
    assert cree["accrues_when_away"] is True
    assert cree["requires_presence"] is True, "defaut prudent : comme une piece"
    assert cree["version"] == 1

    # Le meme nom une seconde fois est refuse, pas duplique.
    doublon = await client.post(
        "/api/contexts", headers=auth_headers, json={"name": nom},
    )
    assert doublon.status_code == 409, doublon.text

    async with async_session() as session:
        jumelle = (await session.execute(
            select(Room).where(Room.foyer_id == foyer, Room.name == nom,
                               Room.deleted_at.is_(None))
        )).scalar_one_or_none()
        assert jumelle is not None, "la piece jumelle n'a pas ete ecrite"

    # Le renommage se propage au jumeau.
    maj = await client.put(
        f"/api/contexts/{cree['id']}", headers=auth_headers,
        json={"name": nom + " bis"},
    )
    assert maj.status_code == 200, maj.text
    assert maj.json()["version"] == 2
    async with async_session() as session:
        renommee = (await session.execute(
            select(Room).where(Room.foyer_id == foyer, Room.name == nom + " bis")
        )).scalar_one_or_none()
        assert renommee is not None, "le renommage n'a pas suivi cote piece"

    supp = await client.delete(f"/api/contexts/{cree['id']}", headers=auth_headers)
    assert supp.status_code == 200, supp.text
    listing = (await client.get("/api/contexts", headers=auth_headers)).json()
    assert all(c["id"] != cree["id"] for c in listing)


@pytest.mark.asyncio
async def test_task_contexts_are_ordered_and_drive_the_legacy_room(client, auth_headers):
    """`context_ids` remplace les rattachements, dans l'ordre donne.

    L'ordre n'est pas cosmetique : la position 0 designe le contexte principal,
    donc celui sous lequel la tache s'affiche — et c'est lui qui alimente le
    `room_id` que lit l'APK diffuse.
    """
    from app.database import async_session
    from app.models.task import Task
    from app.models.room import Room
    from tests.conftest import TEST_FOYER_ID, TEST_USER_ID

    foyer = uuid_lib.UUID(TEST_FOYER_ID)
    noms = ["Ctx alpha", "Ctx beta"]
    ids = []
    for nom in noms:
        r = await client.post("/api/contexts", headers=auth_headers, json={"name": nom})
        if r.status_code == 409:
            listing = (await client.get("/api/contexts", headers=auth_headers)).json()
            ids.append(next(c["id"] for c in listing if c["name"] == nom))
        else:
            assert r.status_code == 201, r.text
            ids.append(r.json()["id"])

    creation = await client.post(
        "/api/tasks", headers=auth_headers,
        json={
            "title": "Tache multi-contexte",
            "assigned_to_ids": [TEST_USER_ID],
            "context_ids": ids,
            "recurrence_rule": {"type": "interval", "every_n_days": 7},
        },
    )
    assert creation.status_code == 201, creation.text
    tache = creation.json()
    assert [c["name"] for c in tache["contexts"]] == noms
    assert [c["position"] for c in tache["contexts"]] == [0, 1]

    # room_id suit le contexte principal, via la piece jumelle.
    async with async_session() as session:
        row = await session.get(Task, uuid_lib.UUID(tache["id"]))
        jumelle = (await session.execute(
            select(Room).where(Room.foyer_id == foyer, Room.name == noms[0],
                               Room.deleted_at.is_(None))
        )).scalar_one_or_none()
        assert jumelle is not None and row.room_id == jumelle.id

    # Inverser l'ordre change le principal, et donc la piece heritee.
    maj = await client.put(
        f"/api/tasks/{tache['id']}", headers=auth_headers,
        json={"context_ids": list(reversed(ids))},
    )
    assert maj.status_code == 200, maj.text
    assert [c["name"] for c in maj.json()["contexts"]] == list(reversed(noms))
    async with async_session() as session:
        row = await session.get(Task, uuid_lib.UUID(tache["id"]))
        jumelle_beta = (await session.execute(
            select(Room).where(Room.foyer_id == foyer, Room.name == noms[1],
                               Room.deleted_at.is_(None))
        )).scalar_one_or_none()
        assert row.room_id == jumelle_beta.id, "la piece heritee n'a pas suivi"

    # Supprimer un contexte detache la tache sans la faire disparaitre.
    await client.delete(f"/api/contexts/{ids[0]}", headers=auth_headers)
    relu = (await client.get(f"/api/tasks/{tache['id']}", headers=auth_headers)).json()
    assert [c["name"] for c in relu["contexts"]] == [noms[1]]
