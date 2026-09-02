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

from app.api.catalog import _implicit_keys, kept_context_keys
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


def test_a_task_is_never_left_without_a_context():
    """Le repli existe pour ca : sans contexte, une tache sort de tout filtre."""
    catalog = get_catalog()
    implicites = _implicit_keys(catalog)
    orphelines = [
        t.key for t in catalog.tasks
        if not kept_context_keys(t, set(), implicites)
    ]
    assert orphelines == [], f"taches sans aucun contexte : {orphelines[:5]}"




@pytest.mark.asyncio
async def test_context_crud(client, auth_headers):
    """Creation, unicite du nom, renommage, suppression."""
    from app.database import async_session
    from tests.conftest import TEST_FOYER_ID

    nom = "Cabane de test"
    foyer = uuid_lib.UUID(TEST_FOYER_ID)

    # Etat de depart propre. La base de dev n'est pas purgee entre les runs, et
    # le nettoyage doit couvrir le nom d'arrivee du renommage : ne purger que
    # le nom de depart laissait un « … bis » vivant qui faisait echouer le run
    # suivant en 409, sur un code pourtant correct.
    async with async_session() as session:
        for modele in (Context,):
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


    # Le renommage se propage au jumeau.
    maj = await client.put(
        f"/api/contexts/{cree['id']}", headers=auth_headers,
        json={"name": nom + " bis"},
    )
    assert maj.status_code == 200, maj.text
    assert maj.json()["version"] == 2

    supp = await client.delete(f"/api/contexts/{cree['id']}", headers=auth_headers)
    assert supp.status_code == 200, supp.text
    listing = (await client.get("/api/contexts", headers=auth_headers)).json()
    assert all(c["id"] != cree["id"] for c in listing)


@pytest.mark.asyncio
async def test_task_contexts_are_ordered(client, auth_headers):
    """`context_ids` remplace les rattachements, dans l'ordre donne.

    L'ordre n'est pas cosmetique : la position 0 designe le contexte principal,
    donc celui sous lequel la tache s'affiche dans toute l'application.
    """
    from tests.conftest import TEST_USER_ID
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


    # Inverser l'ordre change le contexte principal.
    maj = await client.put(
        f"/api/tasks/{tache['id']}", headers=auth_headers,
        json={"context_ids": list(reversed(ids))},
    )
    assert maj.status_code == 200, maj.text
    assert [c["name"] for c in maj.json()["contexts"]] == list(reversed(noms))

    # Supprimer un contexte detache la tache sans la faire disparaitre.
    await client.delete(f"/api/contexts/{ids[0]}", headers=auth_headers)
    relu = (await client.get(f"/api/tasks/{tache['id']}", headers=auth_headers)).json()
    assert [c["name"] for c in relu["contexts"]] == [noms[1]]


@pytest.mark.asyncio
async def test_exports_list_every_context(client, auth_headers):
    """L'export porte TOUS les contextes, pas seulement le principal.

    Un tableau plat force a choisir ; garder le seul contexte principal
    reproduirait la perte d'information que ce chantier corrige. La colonne
    liste les rattachements dans l'ordre, separes par `;`.

    Ce test tape la vraie route : la sous-requete d'agregation compile en SQL
    valide sans rien prouver de son resultat, et une correlation ratee
    multiplierait silencieusement les lignes.
    """
    from tests.conftest import TEST_USER_ID

    # Le test seme sa propre tache multi-contexte : la base de dev n'est pas
    # purgee, et se fier a ce qu'elle contient rendrait l'assertion finale
    # verte ou rouge selon l'ordre des tests precedents.
    ids = []
    for nom in ("Ctx export A", "Ctx export B"):
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
            "title": "Tache export multi",
            "assigned_to_ids": [TEST_USER_ID],
            "context_ids": ids,
            "recurrence_rule": {"type": "interval", "every_n_days": 5},
        },
    )
    assert creation.status_code == 201, creation.text

    taches = await client.get("/api/export/tasks.csv", headers=auth_headers)
    assert taches.status_code == 200, taches.text
    lignes = taches.text.splitlines()
    entete = lignes[0].split(",")
    assert "contexts" in entete, entete
    assert "room" not in entete and "category" not in entete, entete

    completions = await client.get("/api/export/completions.csv", headers=auth_headers)
    assert completions.status_code == 200, completions.text
    assert "contexts" in completions.text.splitlines()[0]

    # Au moins une tache multi-contexte doit montrer ses deux rattachements.
    ligne = next((l for l in lignes if l.startswith("Tache export multi")), None)
    assert ligne is not None, "la tache semee est absente de l'export"
    assert "Ctx export A; Ctx export B" in ligne, ligne


@pytest.mark.asyncio
async def test_stats_split_by_context_totals_match_completions(client, auth_headers):
    """La repartition compte le contexte PRINCIPAL, jamais tous.

    Une tache porte plusieurs contextes : les compter tous ferait qu'une
    completion pese deux ou trois fois, et les parts du graphique ne
    sommeraient plus au nombre de taches reellement faites. Le test compare
    donc la somme des parts au nombre de completions du foyer — c'est
    l'invariant qui casse en premier si la jointure derape.
    """
    from tests.conftest import TEST_USER_ID

    ids = []
    for nom in ("Ctx stat A", "Ctx stat B"):
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
            "title": "Tache stat multi",
            "assigned_to_ids": [TEST_USER_ID],
            "context_ids": ids,
            "recurrence_rule": {"type": "interval", "every_n_days": 3},
        },
    )
    assert creation.status_code == 201, creation.text
    task_id = creation.json()["id"]

    fait = await client.post(f"/api/tasks/{task_id}/complete", headers=auth_headers, json={})
    assert fait.status_code in (200, 201), fait.text

    resp = await client.get("/api/stats/category-frequency?period=all", headers=auth_headers)
    assert resp.status_code == 200, resp.text
    data = resp.json()

    noms = [c["category_name"] for c in data["categories"]]
    assert "Ctx stat A" in noms, noms
    # Le SECOND contexte ne doit PAS apparaitre : il n'est pas le principal.
    assert "Ctx stat B" not in noms, noms


@pytest.mark.asyncio
async def test_every_read_route_survives_without_rooms_or_categories(client, auth_headers):
    """Aucune route de lecture ne casse, tables héritées supprimées.

    Les routes sont ÉNUMÉRÉES DEPUIS L'APP, pas listées à la main. La version
    manuscrite a laissé passer deux 500 : `/api/stats/long-term`, trouvé en
    dogfood, puis `/api/stats/retard-timeline`, trouvé en PRODUCTION par
    l'utilisateur. Une liste écrite à la main mesure ce que son auteur a pensé
    à écrire ; l'écart avec ce que le serveur expose grandit à chaque route
    ajoutée, et il grandit en silence.

    Volontairement large et peu profond : il ne vérifie pas ce que chaque
    route renvoie, seulement qu'aucune ne casse sur un attribut disparu. Les
    assertions métier vivent dans les tests dédiés.
    """
    from app.main import app

    # Seules les GET sans paramètre de chemin : les autres exigent un id réel,
    # et en fabriquer un les transformerait en tests fonctionnels déguisés.
    routes = sorted(
        chemin
        for chemin, methodes in app.openapi()["paths"].items()
        if "get" in methodes and "{" not in chemin
    )
    assert len(routes) > 20, f"énumération suspecte : {len(routes)} routes"

    casses = []
    for route in routes:
        resp = await client.get(route, headers=auth_headers)
        if resp.status_code >= 500:
            casses.append(f"{route} -> {resp.status_code}")
    assert not casses, "routes en erreur serveur : " + ", ".join(casses)


@pytest.mark.asyncio
async def test_retard_timeline_works_when_freeze_is_enabled(client, auth_headers):
    """La courbe de retard passe par le gel, et le gel passe par les contextes.

    Ce test existe parce que le test de fumée ne suffisait pas : il appelait
    bien `/api/stats/retard-timeline`, mais le foyer de test a
    `home_freeze_enabled = false`, donc la branche qui résout les contextes
    n'était jamais atteinte. Un import manquant y est parti EN PRODUCTION et
    n'a été vu que dans les logs du serveur, sur le foyer réel — qui, lui, a
    le gel activé.

    Couvrir une route ne couvre pas ses chemins. Ici la condition à mettre en
    place tient en un booléen, et c'est tout ce qui séparait le test du
    défaut.
    """
    from app.database import async_session
    from app.models.foyer import Foyer
    from tests.conftest import TEST_FOYER_ID

    foyer_id = uuid_lib.UUID(TEST_FOYER_ID)
    async with async_session() as session:
        foyer = await session.get(Foyer, foyer_id)
        ancien = foyer.home_freeze_enabled
        foyer.home_freeze_enabled = True
        await session.commit()

    try:
        for route in ("/api/stats/retard-timeline?period=month",
                      "/api/retard",
                      "/api/suggestions"):
            resp = await client.get(route, headers=auth_headers)
            assert resp.status_code == 200, f"{route} -> {resp.status_code} {resp.text[:200]}"
    finally:
        async with async_session() as session:
            foyer = await session.get(Foyer, foyer_id)
            foyer.home_freeze_enabled = ancien
            await session.commit()


@pytest.mark.asyncio
async def test_presence_prompt_closes_when_days_get_filled(client, auth_headers):
    """Un prompt de présence se referme quand les jours sont remplis AILLEURS.

    Défaut mesuré en production : la pop-up « jours non confirmés » revenait à
    chaque ouverture pour huit jours pourtant tous renseignés. Le générateur
    était juste ; il manquait la branche inverse — `if uncertain: creer(...)`
    sans `else`, et un seul chemin (répondre à la pop-up) refermait le prompt.

    Le test remplit les jours par l'écran de présence, qui est le geste réel de
    l'utilisateur et celui qui ne refermait rien.
    """
    from datetime import date, timedelta
    from app.database import async_session
    from app.models.enums import PromptType
    from app.models.presence_day import PresenceDay
    from app.models.user_prompt import UserPrompt
    from tests.conftest import TEST_FOYER_ID

    foyer = uuid_lib.UUID(TEST_FOYER_ID)
    aujourdhui = date.today()
    trou = [aujourdhui - timedelta(days=n) for n in (3, 2, 1)]
    ancre = aujourdhui - timedelta(days=4)

    async with async_session() as session:
        await session.execute(delete(PresenceDay).where(PresenceDay.foyer_id == foyer))
        await session.execute(delete(UserPrompt).where(UserPrompt.foyer_id == foyer))
        # Un jour confirmé sert d'ancre : sans historique, le générateur ne
        # réclame rien (il ne remonte pas à la nuit des temps).
        session.add(PresenceDay(
            foyer_id=foyer, date=ancre, status="confirmed_present", source="manual",
        ))
        await session.commit()

    # Le check-in constate le trou et crée le prompt.
    resp = await client.post("/api/presence/checkin", headers=auth_headers, json={})
    assert resp.status_code == 200, resp.text

    # Le filtre sur le TYPE porte les deux assertions, et il compte autant dans
    # celle-ci que dans la finale : sans lui, un prompt d'un autre type suffirait
    # a satisfaire « le prompt de presence a bien ete cree », et le test passerait
    # au vert alors que le generateur n'a rien produit. Le check-in evalue aussi
    # les modules et cree des DISCOVER_MODULE — mesure le 2026-09-02, c'est ce
    # qui faisait echouer l'assertion finale sur un foyer pourtant en regle.
    async def prompts_presence_ouverts():
        async with async_session() as session:
            return (await session.execute(
                select(UserPrompt).where(
                    UserPrompt.foyer_id == foyer,
                    UserPrompt.type == PromptType.RESOLVE_PRESENCE,
                    UserPrompt.resolved_at.is_(None),
                )
            )).scalars().all()

    ouverts = await prompts_presence_ouverts()
    assert ouverts, "aucun prompt de présence créé alors que des jours manquent"

    # L'utilisateur remplit son calendrier — sans toucher à la pop-up.
    remplissage = await client.put(
        "/api/presence", headers=auth_headers,
        json=[{"date": j.isoformat(), "status": "absent"} for j in trou],
    )
    assert remplissage.status_code == 200, remplissage.text

    restants = await prompts_presence_ouverts()
    assert not restants, (
        f"{len(restants)} prompt(s) de présence réclament encore des jours "
        f"déjà renseignés"
    )


@pytest.mark.asyncio
async def test_a_prompt_about_a_deleted_task_is_never_served(client, auth_headers):
    """La garde générique referme un prompt dont la condition a disparu.

    Elle existe parce que `if condition: creer_prompt(...)` s'écrit
    naturellement sans `else` : une branche absente ne s'écrit nulle part, donc
    aucune relecture ne la signale. Le cas de la présence l'a montré en
    production ; celui-ci prend un AUTRE type pour vérifier que la garde est
    générique et pas une rustine déguisée.

    Une tâche supprimée est le cas le plus net : répondre au prompt écrirait
    sur une ligne absente, et l'afficher demande à l'utilisateur de se
    prononcer sur ce qu'il vient d'effacer.
    """
    from app.database import async_session
    from app.models.enums import PromptType
    from app.models.task import Task
    from app.models.user_prompt import UserPrompt
    from tests.conftest import TEST_FOYER_ID, TEST_USER_ID

    foyer = uuid_lib.UUID(TEST_FOYER_ID)
    user = uuid_lib.UUID(TEST_USER_ID)

    creation = await client.post(
        "/api/tasks", headers=auth_headers,
        json={
            "title": "Tache vouee a disparaitre",
            "assigned_to_ids": [TEST_USER_ID],
            "recurrence_rule": {"type": "interval", "every_n_days": 4},
        },
    )
    assert creation.status_code == 201, creation.text
    task_id = creation.json()["id"]

    # `BUMP_EFFORT` est porte par le module `auto_adjust` : sans lui, la route
    # filtre le prompt en amont et le test serait vert sans rien exercer.
    active = await client.put(
        "/api/modules/auto_adjust", headers=auth_headers, json={"enabled": True},
    )
    assert active.status_code == 200, active.text

    async with async_session() as session:
        await session.execute(delete(UserPrompt).where(UserPrompt.foyer_id == foyer))
        session.add(UserPrompt(
            user_id=user, foyer_id=foyer,
            type=PromptType.BUMP_EFFORT, priority=5,
            context={"task_id": task_id, "task_title": "Tache vouee a disparaitre"},
        ))
        await session.commit()

    # Tant que la tâche vit, le prompt est servi.
    servi = await client.get("/api/prompts/next", headers=auth_headers)
    assert servi.status_code == 200, f"prompt non servi : {servi.status_code}"

    # L'utilisateur supprime la tâche par un autre chemin.
    suppression = await client.delete(f"/api/tasks/{task_id}", headers=auth_headers)
    assert suppression.status_code in (200, 204), suppression.text

    # Le prompt ne doit plus être servi, ET avoir été refermé en base.
    apres = await client.get("/api/prompts/next", headers=auth_headers)
    assert apres.status_code == 204, (
        f"un prompt parlant d'une tache supprimee a ete servi : {apres.text[:200]}"
    )

    async with async_session() as session:
        restants = (await session.execute(
            select(UserPrompt).where(
                UserPrompt.foyer_id == foyer, UserPrompt.resolved_at.is_(None)
            )
        )).scalars().all()
    assert not restants, "le prompt perime n'a pas ete referme en base"
