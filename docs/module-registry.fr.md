# Registre des modules et découverte progressive

État : décisions arrêtées le 2026-08-17. **P1 à P4 livrés et vérifiés**
(44/44 tests) ; P5 reste à faire. Ce document est la spécification
du chantier. Version anglaise : `module-registry.md`.

## Le problème

L'app est complète et hébergée (prod v0.36.1 au 2026-08-17). Le point noir
est le parcours de première utilisation : un foyer neuf démarrait avec
quasiment tout allumé — gamification, popup de notation d'affinité,
notifications, bonus/malus et ses 13 seuils — face à 895 chaînes d'interface
(mesuré le 2026-08-17).

Le chantier est une **réorganisation**, pas une construction. La
machinerie existe déjà :

- `user_prompts` : file unifiée priorisée, event-driven + cron, avec
  `shown_at`, `resolved_at`, `expires_at`, `grace_until`, résolution
  `DISMISSED`/`ANSWERED`, `context` JSONB figé. 5 types en service.
- `presence_day` + `get_absent_dates` : détection d'absence.
- `objective_propositions` : proposer / accepter / refuser / expirer,
  avec FCM et célébration.
- `backend/app/content/*.md` + `scripts/gen_site_content.py` : le motif
  « source unique → app + site », déjà en production pour le changelog
  et la feuille de route.

## Le registre

Deux niveaux. Le niveau 1 est ce que l'utilisateur voit ; le niveau 2
n'apparaît que si son module est actif.

### Noyau — jamais désactivable

Récurrence, urgence vert/orange/rouge, pièces, catégories, complétion,
**tâches ponctuelles**, catalogue d'import, hors-ligne, historique par
tâche.

Les ponctuelles sont au noyau et non module : le seul signal possible
serait « l'utilisateur crée une tâche sans récurrence », impossible si
le module est éteint. Et une ponctuelle n'ajoute aucune surface — c'est
l'absence de règle de récurrence dans un formulaire existant.

### Les 8 modules

| # | Module | Sous-options | Portée |
|---|--------|--------------|--------|
| 1 | Répartition | assignation, affinités, effort, fair-share | collectif |
| 2 | Motivation | points, badges, streaks, classement | collectif |
| 3 | Bonus / malus | seuils avance, seuils retard | collectif |
| 4 | Objectifs & rattrapage | objectif perso, cockpit, simulation, propositions | collectif |
| 5 | Absences & présence | home freeze, gel par catégorie, WiFi maison | collectif |
| 6 | Ajustement automatique | bump effort, vote fréquence | collectif |
| 7 | Statistiques & historique | courbes, historique enrichi | perso |
| 8 | Rappels | les 6 notifications | perso |

**Règle de portée** : collectif si le module change le modèle partagé,
perso s'il ne change que ce que je vois ou reçois. Elle s'applique au
module, pas à la sous-option : un module est collectif dès qu'*une* de
ses sous-options l'est. Les modules 2 et 4 sont mixtes (classement et
cockpit collectifs, badges et objectif perso personnels) — leur flag est
donc collectif, leurs sous-options perso restent perso.

Corollaire : le `AND(foyer, user)` sur `gamification_enabled` était un
défaut de modèle, pas un réglage confus — il laissait un membre masquer
unilatéralement une décision collective. **Modèle corrigé en P1** : l'état
du module est collectif, et `user_settings.gamification_enabled` est
requalifié en préférence d'affichage personnelle (`motivation.hide_for_me`)
plutôt que supprimé — sinon on rallumait la gamification chez ceux qui
l'avaient coupée. **Front aligné en P2** : `gamificationVisibleFlow` lit
désormais le module `motivation`, et ne retombe sur le drapeau hérité que
tant que `/api/modules` n'a jamais répondu.

## Décisions arrêtées

**1. Sémantique du OFF — mixte selon la nature.**
Ce qui s'accumule tourne en fond (points, badges, streaks, stats,
retard/pace/maintenance-rate) ; ce qui interrompt n'est pas généré du
tout (notifications, popups, prompts). Le calcul en fond coûte zéro :
`/api/retard` alimente déjà la home, `points_earned` est déjà écrit sur
chaque complétion.

**2. Le réveil — révélation complète.**
À l'activation, tout l'historique apparaît. Aucune colonne
`enabled_since`, aucune condition supplémentaire : les données existent.
La proposition devient « vous avez déjà 187 points, on vous les
montre ? » plutôt que « voulez-vous essayer ? ».

Le fair-share ne pose pas le problème de dette rétroactive redouté : il
tourne déjà sur une fenêtre glissante de 30 jours
(`fair_share_status`, cutoff à 30 jours), donc borné et auto-nettoyant.

**3. Qui décide — n'importe quel membre.**
Cohérent avec l'usage constaté : pièces, catégories, tâches et
`bonus-config` sont déjà ouverts à tous sans incident. Toute bascule
collective émet une notification info aux autres membres et une ligne
dans `change_log`.

Effet de bord à traiter : `PUT /bonus-config` n'a aucun garde alors que
`PUT /foyers/{id}` est admin-only. L'incohérence est levée par le bas.

**4. Déclencheur — signal contextuel prioritaire, seuil d'usage en repli.**

Signal fort, donnée déjà calculée (4 modules) :

| Module | Signal |
|--------|--------|
| Répartition | un 2ᵉ membre rejoint le foyer |
| Absences & présence | absence ≥ 3 jours détectée |
| Ajustement automatique | tâche systématiquement en retard |
| Objectifs & rattrapage | `/api/retard` dépasse un seuil |

Pas de signal, seuil d'usage seulement (4 modules) : Motivation,
Statistiques, Bonus/malus (exige Motivation actif d'abord), Rappels
(actif dès J0, c'est le module de rétention).

Le signal seul ne couvre que la moitié du registre : le repli n'est pas
un confort, c'est ce qui empêche 4 modules de rester invisibles à vie.

**5. Débit — budget séparé, tenu par le serveur.**
Une seule file, deux compteurs.

- Prompts de travail (les 5 types existants) : tolérance 2 h, inchangée.
- Prompts de découverte : 1 par semaine au maximum, jamais avant J+7,
  jamais deux dans la même session, 30 jours de silence après un refus.

Le budget vit en base (`user_prompts` porte déjà `resolved_at` et
`grace_until`) et non dans `HomeViewModel`, sinon une réinstallation
remet le compteur à zéro, deux appareils divergent, et les deux membres
d'un foyer reçoivent chacun la même proposition d'un choix pourtant
collectif.

**6. Module refusé** — reproposé une fois après 30 jours sur un nouveau
signal, abandon définitif au 2ᵉ refus. Sans coût, parce que le module
reste atteignable dans Réglages.

**7. Surface dans l'app — Réglages devient l'arbre des modules.**
Pas de 10ᵉ écran. Huit des futures sous-options y sont déjà (6
notifications, popup d'affinité, WiFi maison), rangées par hasard
historique. Trois blocs : Fonctionnalités, Mon foyer, Mon compte.

Conséquence : le rythme d'un module par semaine devient un défaut, pas
un plafond. L'impatient ouvre l'écran, le passif se laisse guider.

**8. État d'un foyer neuf — noyau + Rappels.**
Les 8 modules éteints. À J0 le foyer est solo — le wizard crée un seul
membre, l'invitation QR est un écran séparé : Répartition, classement et
fair-share ne sont pas « lourds », ils sont vides de sens. Et laisser
Répartition éteint préserve le meilleur signal du dispositif.

**9. Foyers existants — tout reste actif.**
Le nouveau régime ne s'applique qu'aux foyers créés après la bascule.

**10. Contenu — `modules.yaml`, créé en P1.**
Dans le dossier de contenu du backend, à côté de `catalog.yaml`. Source
unique, multi-locale comme lui, alimentant les trois consommateurs :
cartes du site, écran Fonctionnalités, texte des propositions. Chaque
module porte un `pitch` (registre commercial) et un `details` (registre
technique). Un test confronte chaque `state_field` aux métadonnées
SQLAlchemy : une colonne renommée casse la suite au lieu de produire un
interrupteur qui n'écrit nulle part.

## Le site

Dix cartes au maximum. Les cartes restent à la granularité **bénéfice**
et non module : `modules.yaml` porte une correspondance explicite carte →
module(s). Aligner 1:1 échangerait trois cartes concrètes
(« L'un préfère l'aspirateur, l'autre la vaisselle ») contre un nom de
catégorie, et mettrait « Bonus / malus » et ses 13 seuils en vitrine.

| # | Carte | Module |
|---|-------|--------|
| 1 | Tâches récurrentes | noyau |
| 2 | Urgence en un coup d'œil | noyau |
| 3 | Part équitable | Répartition |
| 4 | Préférences de chacun | Répartition |
| 5 | Pénibilité | Répartition |
| 6 | Gestion des absences | Absences |
| 7 | Rattraper le retard | Objectifs — nouvelle |
| 8 | Motivation, si vous voulez | Motivation — nouvelle |
| 9 | L'app grandit avec vous | principe — nouvelle |
| 10 | Hors-ligne | propriété |

La carte « Sans compte, sans pistage » est supprimée : elle répète le
hero mot pour mot. La carte 9 est imposée par la décision 8 — sans elle
le site promet dix choses que l'installation ne montre pas.

Modules sans carte (Bonus/malus, Ajustement automatique, Statistiques,
Rappels) : visibles dans le détail au clic. C'est un choix éditorial à
réassumer à chaque nouveau module, pas un automatisme.

Détail au clic : `<details>` / `<dialog>` natifs. Le site n'embarque
aujourd'hui qu'un script inline (l'année du copyright) ; une modale JS
serait sa première dépendance.

## Le balayage d'application (P3)

Chaque module eteint doit cesser de s'afficher — et surtout cesser
d'interrompre. Les deux moities n'ont pas la meme valeur : masquer un ecran
est cosmetique, ne plus poser de question ne l'est pas.

### Cote serveur — le canal d'interruption

`user_prompts` est le seul endroit d'ou l'app parle sans qu'on lui demande.
Deux gardes, pas une :

- **a la generation** : `generate_bump_prompts` (auto_adjust),
  `generate_vote_prompts_for_task` et `generate_expired_vote_prompts`
  (repartition) sortent immediatement si leur module est coupe ;
- **a la lecture** : `GET /prompts/next` filtre les types dont le module est
  coupe. Cette seconde garde couvre ce que la premiere ne peut pas voir — la
  file DEJA constituee au moment ou l'on eteint. Sans elle, couper une
  fonctionnalite et continuer d'etre interroge par elle pendant des jours.

`PROMPT_TYPE_MODULE` fait la correspondance, et un test verifie que **tout**
`PromptType` y figure : un type non rattache ne serait jamais filtre, en
silence et sur ce seul type.

### Cote client

| Surface | Module | Note |
|---------|--------|------|
| Onglet Stats (nav) | statistics **ou** motivation | l'ecran porte les deux |
| Onglet Badges | motivation | |
| Onglet Historique | statistics | |
| Message « plus que ta part » | repartition | etait sur la gamif, a tort |
| Cockpit de rattrapage (home) | objectives | |
| Carte Objectifs (stats) | objectives | |
| Bloc Presence (foyer) | presence | |
| Seuils bonus/malus (foyer) | bonus_malus | |
| Bloc Notifications | reminders | P2 |
| Popup d'affinite | repartition | P2 |
| Tout l'UI gamif | motivation | P2, porte centrale |

Les onglets de Stats sont desormais dispatches sur une **identite** et non sur
leur position : cacher un onglet decalait sinon le contenu de tous les
suivants.

`moduleEnabled(cle)` (`android/…/ui/common/ModuleGate.kt`) lit DataStore depuis n'importe
quel composable, sur le meme motif que la porte gamif d'origine. Son repli est
`true` : tant que `/api/modules` n'a pas repondu on montre, parce que faire
disparaitre un ecran le temps d'un aller-retour est pire que l'afficher une
seconde de trop.

## Les propositions contextuelles (P4)

Sixieme type dans `user_prompts` : `DISCOVER_MODULE`. Le premier qui ne
demande pas une information mais propose d'elargir le perimetre — et le seul
dont la regle de diffusion est **inversee** : servi si le module vise est
ETEINT. Cette inversion vit dans le code, pas dans le schema, et
`INVERTED_PROMPT_TYPES` la declare pour que le garde anti-derive distingue
« type non classe » (bug) de « volontairement hors du filtre normal ».

Le texte est fige dans le `context` a la creation, depuis modules.yaml — meme
convention que les cinq autres types. La proposition dit donc exactement ce
que disait le module au moment ou le signal a eu lieu.

### Le budget

`backend/app/services/module_discovery.py`, tenu en base et non dans le client :

Les valeurs sont celles de la decision 5 ci-dessus ; elles ne sont pas
recopiees ici. Ce tableau dit ou elles VIVENT, pour qu'on les regle a un seul
endroit :

| Regle | Constante |
|-------|-----------|
| Age minimum du foyer | `MIN_FOYER_AGE_DAYS` |
| Entre deux propositions | `MIN_DAYS_BETWEEN_PROPOSALS` |
| Silence apres un refus | `REFUSAL_COOLDOWN_DAYS` |
| Abandon definitif | `MAX_REFUSALS` |
| Seuil de detection d'absence | `ABSENCE_PROPOSAL_THRESHOLD_DAYS` (api/presence.py) |

Le compteur porte sur la **creation** et non la resolution : une proposition
ignoree a quand meme consomme son creneau. Compter les resolutions laisserait
une proposition sans reponse rouvrir la porte a chaque passage du cron.

Et une proposition expire au bout d'une semaine, sinon elle bloquerait seule
le creneau suivant — puisque le budget compte les creations.

### Les signaux cables

| Signal | Module | Ou |
|--------|--------|-----|
| un 2ᵉ membre rejoint | repartition | `api/pairing.py`, a l'adhesion |
| retour d'absence ≥ 3 j | presence | `api/presence.py`, au check-in WiFi |
| tache systematiquement en retard | auto_adjust | `services/prompts.py` |

Deux choix a garder en tete.

**L'absence se propose au RETOUR, pas pendant.** Proposer une gestion
d'absence a quelqu'un qui est justement absent, c'est l'interruption en
vacances que tout le chantier combat. Le check-in WiFi est le signal « on est
rentres ». Les jours de presence continuent d'etre enregistres module eteint —
c'est de la donnee qui s'accumule, pas une interruption (decision 1), et c'est
ce qui rend la detection possible.

**La garde de P3 sur le bump est devenue un aiguillage.** Le critere
« systematiquement en retard » est exactement le signal qui justifie
l'ajustement automatique. Le calculer puis le jeter parce que le module est
eteint aurait rendu la fonctionnalite improposable aux foyers qui en ont le
plus besoin. On calcule, puis on route : prompt de travail si le module est
actif, proposition sinon.

### Pas encore cable

- `backlog_threshold_reached` (objectives) : le retard est calcule, le seuil
  reste a poser.
- Les seuils d'usage des modules sans signal (motivation, statistics,
  bonus_malus). Inchange depuis le debut : aucune donnee ne permet de les
  fixer. A instrumenter avant d'arbitrer.

## Les cinq phases

| Phase | Contenu | Dogfood |
|-------|---------|---------|
| P1 ✅ | modules.yaml, colonnes, API, correction du AND | aucun, invisible |
| P2 ✅ | Réglages → catalogue | émulateur, foyer neuf |
| P3 ✅ | Application des modules | Pixel 7 `ts_test`, foyer neuf |
| P4 ✅ | Propositions contextuelles | `ts_test`, boucle complète |
| P5 | Site | après, une fois les textes éprouvés |

Le site vient en dernier parce que ses textes et ceux des propositions
sortent du même fichier : les rédiger après avoir vu ce qui convainc
vaut mieux que les rédiger à l'aveugle.

## Les trois backends (état au 2026-08-17)

| Canal | Où | Adresse | Sert à |
|-------|-----|---------|--------|
| prod | Scaleway <prod-host> | `api.torchonsetserviettes.app` | l'app publique |
| beta | <beta-host>, `~/deploiements/ts-beta` | `<beta-host-ip>:8002` | dogfood P3/P4 |
| dev | <beta-host>, `~/deploiements/ts-dev` | `<beta-host-ip>:5438` | alembic + pytest |

Avant le 2026-08-17, `ts.api.base.url.beta` pointait sur la **même** URL
Scaleway que la prod : le canal beta existait dans le build Gradle
(`applicationId` suffixé `.beta`, `TAILSCALE_AUTOLOGIN`, marqueur de build)
mais n'avait aucun serveur à lui, donc tout test grandeur nature écrivait
dans les vraies données. La stack beta comble ce trou.

Pas de Caddy ni de TLS sur la beta : elle n'est joignable que par le
tailnet, qui chiffre déjà. Un certificat exigerait un DNS public, donc une
exposition publique — l'inverse de ce qu'on veut d'un serveur de test.
Il n'y a pas d'auto-login par IP (routes retirées le 2026-10-05) : chaque
appareil passe par le wizard, qui est précisément l'objet du chantier.

Le code y arrive par `tar | ssh` depuis le poste et non par `git pull` : le
dépôt est privé, et un serveur de dogfood doit pouvoir servir du travail pas
encore commité.

## Dogfood — les deux environnements

Le multi-foyer est pleinement supporté (`allFoyers`, bascule via
`TokenManager`, scoping serveur par foyer). Vous pouvez donc créer un
second foyer réel sur vos téléphones et vivre le parcours, foyer
principal intact.

Mais `addFoyerMode` du wizard saute les étapes « Toi » et « Recovery »
(3 étapes au lieu de 5) : un second foyer ne teste **jamais**
l'installation à zéro. D'où l'AVD `ts_test` (Pixel 7, android-35,
`google_apis_playstore_ps16k` x86_64, 2 Go, 6 Go de données), sur la
même recette que les autres AVD de test du mainteneur. Les deux sont complémentaires.

Chaîne vérifiée le 2026-08-17 : l'émulateur atteint la beta à travers le
NAT du poste et son Tailscale (`GET /api/auth/auto` → 200 reçu côté
serveur). Aucun client Tailscale à installer dans l'AVD.

Première observation de dogfood, **corrigée dans la foulée** : au tout
premier lancement, avant tout écran de bienvenue, Android affichait la
demande de permission de notifications — on faisait trancher un canal de
rappels à quelqu'un qui ignorait encore ce que fait l'app.
`MainActivity.onCreate` la lançait à chaque démarrage à froid. Elle vit
désormais dans Réglages, déclenchée par l'activation d'une notification
(`SettingsScreen.onNotifToggle`). Le réglage métier reste indépendant de
la permission système : un refus n'efface pas la préférence.

## Observations de dogfood en attente

- Un foyer qui passe les etapes Pieces et Taches du wizard arrive sur une home
  vide affichant « Tout est frais / rien d'urgent ». Il n'y a rien a etre
  frais : le vide se presente comme un succes. A traiter dans le parcours de
  premiere utilisation.

## Répétition sur les données de prod (2026-08-18)

Migration rejouée sur un dump **frais de prod** (18 foyers, 16 users, 101
tâches, 454 complétions, 66 prompts), restauré dans une base jetable. Résultat :

- 108 lignes collectives (18 × 6) et 38 perso (19 adhésions actives × 2),
  aucun foyer incomplet, **aucun module éteint** ;
- les 2 prompts `BUMP_EFFORT` en attente sont **toujours servis** — vérifié en
  interrogeant le vrai backend, pas en relisant la table ;
- complétion, check-in présence, `/sync`, et les 3 jobs nocturnes passent ;
- la double écriture `motivation` → `foyers.gamification_enabled` fonctionne
  (ce que lit l'APK déployé).

La répétition a trouvé **deux défauts que le raisonnement avait manqués** :

**Un refus implicite brûlait le budget.** L'APK déployé ne connaît pas
`DISCOVER_MODULE` : son `else -> onDismiss()` le résout en `DISMISSED` sans
rien afficher. Deux occurrences et le module était abandonné à vie, sans que
personne n'ait rien vu. Un refus ne compte désormais que s'il porte
`payload.declined` — le marqueur que seul un client à jour envoie.

**Un nouveau membre d'un vieux foyer retombait sur les défauts J0.** Un
appareil ré-appairé crée un NOUVEL utilisateur, donc sans lignes perso : dans
un foyer grandfathered il aurait eu `statistics` éteint pendant que son
conjoint gardait tout. Un foyer portant des lignes collectives est antérieur à
la bascule ; ses modules perso sans ligne valent donc actif.

### Ordre de bascule en prod

Le backend AVANT tout APK, règle de synchronisation de `CLAUDE.md` : le
serveur sert `foyers.gamification_enabled` en double tant que l'APK diffusé la
lit. L'inverse casserait les installations en cours.

## Reste à décider en P4

Les seuils d'usage exacts des 4 modules sans signal (Motivation à
combien de complétions, Statistiques à quel volume d'historique). Aucune
donnée ne permet de les fixer aujourd'hui : `suggestion_dismissals` ne
trace que les suggestions de la home, pas les prompts. À instrumenter
avant d'arbitrer.
