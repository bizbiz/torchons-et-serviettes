# Registre des modules et découverte progressive

État : décisions arrêtées le 2026-08-17. **Les cinq phases (P1 à P5) sont livrées** :
registre, Réglages, application des modules, propositions contextuelles, cartes du site.
Ce document est la spécification du chantier et dit ce qui a été décidé depuis. Version
anglaise : [`module-registry.md`](module-registry.md).

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

Récurrence, urgence vert/orange/rouge, contextes (qui ont remplacé les pièces et les
catégories, voir [`contexts.fr.md`](contexts.fr.md)), complétion, **tâches ponctuelles**,
catalogue d'import, hors-ligne, historique par tâche.

Les ponctuelles sont au noyau et non module : le seul signal possible
serait « l'utilisateur crée une tâche sans récurrence », impossible si
le module est éteint. Et une ponctuelle n'ajoute aucune surface — c'est
l'absence de règle de récurrence dans un formulaire existant.

### Les modules

| # | Module | Sous-options | Portée |
|---|--------|--------------|--------|
| 1 | Répartition | assignation, affinités, effort, fair-share | collectif |
| 2 | Motivation | points, badges, streaks, classement | collectif |
| 3 | Bonus / malus | seuils avance, seuils retard | collectif |
| 4 | Objectifs & rattrapage | objectif perso, cockpit, simulation, propositions | collectif |
| 5 | Absences & présence | home freeze, contextes concernés, WiFi maison | collectif |
| 6 | Ajustement automatique | bump effort, vote fréquence | collectif |
| 7 | Statistiques & historique | courbes, historique enrichi | perso |
| 8 | Rappels | les 6 notifications | perso |
| 9 | À faire aujourd'hui (ajouté le 2026-11-18, allumé par défaut) | rappels 12/16/20 h | collectif |

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
(`STATUS_WINDOW_DAYS`, `backend/app/services/fair_share.py`), donc borné et auto-nettoyant.

**3. Qui décide — n'importe quel membre.**
Cohérent avec l'usage constaté : contextes, tâches et
`bonus-config` sont déjà ouverts à tous sans incident. Toute bascule
collective laisse une ligne dans `change_log`, signée dans `module_states`.
Les autres membres en sont informés à leur prochaine ouverture (révisé le
2026-11-16, pas de push) : une activation par le guide du module
(`guide_due`), une coupure par une carte sur l'Accueil, alimentée par
`changed_by_name` et `changed_at` de `GET /api/modules`.

`PUT /bonus-config` reste ouvert à tout membre, alors que `PUT /foyers/{id}`
est admin-only : tranché le 2026-10-05, l'incohérence est levée par le bas.

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
- Prompts de découverte : 1 tous les 5 jours au maximum (plancher en dur, une
  semaine jusqu'au 2026-11-03), jamais avant J+7, jamais deux dans la même
  session, 30 jours de silence après un refus.

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

Révisé le 2026-11-16 (Quentin, `/grill-me`) : un écran par portée.
L'écran Foyer porte les modules collectifs (« Fonctionnalités du foyer »),
Réglages ne garde que le personnel : modules personnels et sous-options
personnelles. La gamification n'y existe plus que comme « Masquer points et
séries pour moi » (`hide_for_me`) : un membre la masque, il ne l'allume pas.
Une sous-option personnelle dont le module est coupé dans le foyer courant
reste visible, grisée, avec une ligne qui renvoie aux réglages du foyer.
L'ancien interrupteur « gamification du foyer » de l'écran Foyer est retiré.
Refusés : un seul écran à deux sections, le regroupement par module, la
bascule réservée à l'admin, la notification push.

Conséquence : le rythme d'un module tous les 5 jours devient un défaut, pas
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

## Le site (P5, livré)

Dix cartes au maximum (`MAX_CARDS` dans `scripts/gen_site_content.py`). Les cartes restent
à la granularité **bénéfice** et non module : la section `site_cards` de `modules.yaml`
porte une correspondance explicite carte → module(s). Aligner 1:1 échangerait trois cartes
concrètes (« L'un préfère l'aspirateur, l'autre la vaisselle ») contre un nom de
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

Chaque module éteint doit cesser de s'afficher — et surtout cesser
d'interrompre. Les deux moitiés n'ont pas la même valeur : masquer un écran
est cosmétique, ne plus poser de question ne l'est pas.

### Côté serveur — le canal d'interruption

`user_prompts` est le seul endroit d'où l'app parle sans qu'on lui demande.
Deux gardes, pas une :

- **à la génération** : `generate_bump_prompts` (auto_adjust),
  `generate_vote_prompts_for_task` et `generate_expired_vote_prompts`
  (répartition) sortent immédiatement si leur module est coupé ;
- **à la lecture** : `GET /prompts/next` filtre les types dont le module est
  coupé. Cette seconde garde couvre ce que la première ne peut pas voir — la
  file DÉJÀ constituée au moment où l'on éteint. Sans elle, couper une
  fonctionnalité et continuer d'être interrogé par elle pendant des jours.

`PROMPT_TYPE_MODULE` fait la correspondance, et un test vérifie que **tout**
`PromptType` y figure : un type non rattaché ne serait jamais filtré, en
silence et sur ce seul type.

### Côté client

| Surface | Module | Note |
|---------|--------|------|
| Onglet Stats (nav) | statistics **ou** motivation | l'écran porte les deux |
| Onglet Badges | motivation | |
| Onglet Historique | statistics | |
| Message « plus que ta part » | repartition | était sur la gamif, à tort |
| Cockpit de rattrapage (home) | objectives | |
| Carte Objectifs (stats) | objectives | |
| Bloc Présence (foyer) | presence | |
| Seuils bonus/malus (foyer) | bonus_malus | |
| Bloc Notifications | reminders | P2 |
| Popup d'affinité | repartition | P2 |
| Tout l'UI gamif | motivation | P2, porte centrale |
| Points dans le texte de l'historique | motivation + `hide_for_me` | serveur, `points_visibility.py` |
| Lien agenda du profil | motivation **ou** presence | sans points : « agenda de présence » |

Les onglets de Stats sont désormais dispatchés sur une **identité** et non sur
leur position : cacher un onglet décalait sinon le contenu de tous les
suivants.

`moduleEnabled(cle)` (`ui/common/ModuleGate.kt`, côté Android) lit DataStore depuis
n'importe quel composable, sur le même motif que la porte gamif d'origine. Son repli est
`true` : tant que `/api/modules` n'a pas répondu on montre, parce que faire
disparaître un écran le temps d'un aller-retour est pire que l'afficher une
seconde de trop.

## Les propositions contextuelles (P4)

Sixième type dans `user_prompts` : `DISCOVER_MODULE`. Le premier qui ne
demande pas une information mais propose d'élargir le périmètre — et le seul
dont la règle de diffusion est **inversée** : servi si le module visé est
ÉTEINT. Cette inversion vit dans le code, pas dans le schéma, et
`INVERTED_PROMPT_TYPES` la déclare pour que le garde anti-dérive distingue
« type non classé » (bug) de « volontairement hors du filtre normal ».

Le texte est figé dans le `context` à la création, depuis modules.yaml — même
convention que les cinq autres types. La proposition dit donc exactement ce
que disait le module au moment où le signal a eu lieu.

### Le budget

`backend/app/services/module_discovery.py`, tenu en base et non dans le client.
Les valeurs sont celles de la décision 5 ci-dessus ; elles ne sont pas
recopiées ici. Ce tableau dit où elles VIVENT, pour qu'on les règle à un seul
endroit :

| Règle | Constante |
|-------|-----------|
| Âge minimum du foyer | `MIN_FOYER_AGE_DAYS` |
| Entre deux propositions | `MIN_DAYS_BETWEEN_PROPOSALS` |
| Silence après un refus | `REFUSAL_COOLDOWN_DAYS` |
| Abandon définitif | `MAX_REFUSALS` |
| Seuil de détection d'absence | `ABSENCE_PROPOSAL_THRESHOLD_DAYS` (api/presence.py) |

Le compteur porte sur la **création** et non la résolution : une proposition
ignorée a quand même consommé son créneau. Compter les résolutions laisserait
une proposition sans réponse rouvrir la porte à chaque passage du cron.

Et une proposition expire au bout de `MIN_DAYS_BETWEEN_PROPOSALS` jours, sinon
elle bloquerait seule le créneau suivant — puisque le budget compte les créations.

### Les signaux câblés

| Signal | Module | Où |
|--------|--------|-----|
| un 2ᵉ membre rejoint | repartition | `api/pairing.py`, à l'adhésion |
| retour d'absence ≥ 3 j | presence | `api/presence.py`, au check-in WiFi |
| tâche systématiquement en retard | auto_adjust | `services/prompts.py` |

Deux choix à garder en tête.

**L'absence se propose au RETOUR, pas pendant.** Proposer une gestion
d'absence à quelqu'un qui est justement absent, c'est l'interruption en
vacances que tout le chantier combat. Le check-in WiFi est le signal « on est
rentrés ». Les jours de présence continuent d'être enregistrés module éteint —
c'est de la donnée qui s'accumule, pas une interruption (décision 1), et c'est
ce qui rend la détection possible.

**La garde de P3 sur le bump est devenue un aiguillage.** Le critère
« systématiquement en retard » est exactement le signal qui justifie
l'ajustement automatique. Le calculer puis le jeter parce que le module est
éteint aurait rendu la fonctionnalité improposable aux foyers qui en ont le
plus besoin. On calcule, puis on route : prompt de travail si le module est
actif, proposition sinon.

### Les seuils d'usage (décidés le 2026-11-03)

Les quatre modules sans signal câblé se proposent sur un seuil, évalué chaque
nuit à 03:45 UTC (`services/module_usage_triggers.py`, après l'instantané de
retard de 03:15), dans l'ordre du registre, une proposition au plus par foyer
et par passage :

| Module | Seuil (provisoire, à revoir au lancement) |
|--------|-------------------------------------------|
| motivation | 20 validations vivantes dans le foyer |
| bonus_malus | Motivation active depuis 14 jours |
| objectives | 3 tâches en retard sur chacun des 3 derniers instantanés |
| statistics | la plus ancienne validation vivante a 30 jours |

Un seuil reste vrai une fois atteint : sans garde, le premier module de la liste
reprendrait le créneau à chaque expiration. Un module proposé depuis moins de
`REFUSAL_COOLDOWN_DAYS` jours est donc sauté, ce qui laisse passer le suivant.
`statistics` est PERSONNEL : la proposition ne va qu'aux membres qui ne l'ont
pas, état lu membre par membre (`_recipients` dans `module_discovery.py`).

### Les pages d'explication (décidées le 2026-11-03)

Chaque module porte dans modules.yaml un `guide` (`why`, `how`), affiché avec
`details` et `example` : à quoi ça sert, pourquoi, comment bien l'utiliser.
Montré **une fois par membre** (table `module_guide_views`, par utilisateur et
non par foyer) : dans la proposition, à l'activation manuelle dans Réglages, ou,
pour un module collectif activé par un autre membre, à la prochaine ouverture
de l'app (`guide_due` dans `GET /api/modules`, une page par ouverture). Ensuite
un ⓘ à côté de l'interrupteur la rouvre. La migration `b8c4d2e6f1a3` marque
comme vues les pages des modules déjà actifs : les foyers existants ne reçoivent
aucune page à la mise à jour.

## Les cinq phases

| Phase | Contenu | État |
|-------|---------|------|
| P1 | modules.yaml, colonnes, API, correction du AND | livré |
| P2 | Réglages → catalogue | livré |
| P3 | Application des modules | livré |
| P4 | Propositions contextuelles, seuils d'usage, pages d'explication | livré |
| P5 | Cartes du site (`site_cards`) | livré |

Le site est venu en dernier parce que ses textes et ceux des propositions
sortent du même fichier : les rédiger après avoir vu ce qui convainc
vaut mieux que les rédiger à l'aveugle.

## Permission de notifications

Au tout premier lancement, avant tout écran de bienvenue, Android affichait la
demande de permission de notifications — on faisait trancher un canal de
rappels à quelqu'un qui ignorait encore ce que fait l'app.
`MainActivity.onCreate` la lançait à chaque démarrage à froid. Elle vit
désormais dans Réglages, déclenchée par l'activation d'une notification
(`SettingsScreen.onNotifToggle`). Le réglage métier reste indépendant de
la permission système : un refus n'efface pas la préférence.

## Deux pièges de bascule, trouvés en rejouant la migration

La migration a été rejouée sur une copie réelle de la base avant la bascule. Elle a trouvé
**deux défauts que le raisonnement avait manqués** :

**Un refus implicite brûlait le budget.** Un APK diffusé avant `DISCOVER_MODULE` ne le
connaît pas : son `else -> onDismiss()` le résout en `DISMISSED` sans
rien afficher. Deux occurrences et le module était abandonné à vie, sans que
personne n'ait rien vu. Un refus ne compte désormais que s'il porte
`payload.declined` — le marqueur que seul un client à jour envoie.

**Un nouveau membre d'un vieux foyer retombait sur les défauts J0.** Un
appareil ré-appairé crée un NOUVEL utilisateur, donc sans lignes perso : dans
un foyer grandfathered il aurait eu `statistics` éteint pendant que son
conjoint gardait tout. Un foyer portant des lignes collectives est antérieur à
la bascule ; ses modules perso sans ligne valent donc actif.

### Ordre de bascule

Le backend AVANT tout APK, règle de synchronisation de
[`architecture.fr.md`](architecture.fr.md) § Ordre de synchronisation : le
serveur sert `foyers.gamification_enabled` en double tant que l'APK diffusé la
lit. L'inverse casserait les installations en cours.

La route héritée `PUT /api/foyers/{id}/gamification`, derrière l'ancien
interrupteur Foyer des APK jusqu'à la v0.44.6, écrit le module `motivation`
depuis le 2026-11-16 (`set_module_enabled`) : elle n'écrivait que la colonne,
que l'app ne lit plus, et cet interrupteur ne faisait rien. Elle part quand le
version gate aura dépassé ces APK.
