# Architecture — vue d'ensemble de Torchons & Serviettes

Version anglaise : [`architecture.md`](architecture.md).

Torchons & Serviettes est une application de tâches ménagères collaboratives pour un foyer
(2 membres ou plus), avec gamification et partage équitable (« fair-share »). Ce document
donne la carte : les couches, la surface d'API, les règles de récurrence et d'urgence, l'ordre
de déploiement client/serveur et le modèle d'appareils. Chaque section renvoie au détail.

## Architecture

- **Backend** FastAPI (Python) : API REST, synchronisation hors ligne et authentification JWT.
- **Android** Kotlin natif : Jetpack Compose, Room et Retrofit.
- **Base de données** PostgreSQL 16 : UUID partout, suppressions logiques (soft delete) et
  `change_log` pour la synchronisation.
- **Contextes** : une tâche en porte plusieurs. Ils ont remplacé les catégories et les
  pièces, supprimées en base. Règles de visibilité, deux drapeaux et suggestions :
  [`contexts.fr.md`](contexts.fr.md).
- **Tâche solo** (un seul assigné) : visible par lui seul, quel que soit son créateur. Toute
  route ou opération `/sync` qui lit ou écrit une tâche, ou une ligne qui y pointe, passe par
  `visible_tasks` / `visible_to` (`backend/app/utils/scoping.py`) ;
  `tests/test_solo_task_privacy.py` balaie toutes les routes GET.
- **Notifications de validation** : par REST (`send_completion_event`) et par `/sync`
  (`send_sync_completions`, une par membre et par appel). L'application valide par `/sync`.
- **Cache et hors ligne** : l'application est hors ligne d'abord ; les règles du cache Room, du
  rafraîchissement et de l'état dérivé sont dans
  [`cache-conventions.fr.md`](cache-conventions.fr.md).
- **Modules et découverte progressive** : un registre à deux niveaux (noyau et modules),
  décrit dans [`module-registry.fr.md`](module-registry.fr.md).

## API

Base : `/api`.

| Groupe | Routes |
|--------|--------|
| Auth | `GET /auth/me`, `/auth/users` — jetons émis par `/onboarding`, `/pairing`, `/recovery` |
| Tâches | `GET/POST/PUT/DELETE /tasks` |
| Contextes | `GET /catalog/contexts` (catégories et pièces : couche de compatibilité) |
| Validations | `POST /tasks/{id}/complete`, `GET /completions` |
| Synchronisation | `POST /sync` |
| Gamification | `GET /leaderboard`, `/streaks`, `/stats/*` |
| Fair-share | `GET /fair-share/status`, `/fair-share/history`, `GET/PUT /fair-share/config` |
| Notifications | `POST/DELETE /notifications/register` |
| Appareils | `GET /devices`, `POST /devices/register`, `/devices/{id}/revoke` |

## Récurrence

La récurrence d'une tâche est un objet JSONB. Cinq types sont vivants :

- `{"type":"interval","every_n_days":3}`
- `{"type":"monthly_variable","days_per_month":{"1":30,...}}` — 12 mois, de 1 à 365 jours.
- `{"type":"uncertain"}` — cadence inférée, aucune échéance affichée.
- `{"type":"calendar","months":[5],"day":15,"grace_days":0}` — ancré sur le calendrier et non
  sur la dernière validation : aucune dérive. Voir
  [`contexts.fr.md`](contexts.fr.md) § Récurrence calendaire.
- `{"type":"anniversary","every_n_months":12}` — ancré sur la première validation vivante
  (`first_completed_at`), même section. La liste des types est `LIVING_RULE_TYPES` côté
  backend et `RecurrenceTypes.LIVING` côté Kotlin, gardée par un test.

À la normalisation, `weekdays` et `custom` deviennent `interval`, et `monthly` devient
`calendar` (12 mois). Les lignes anciennes ont été converties par la migration `d4ec241658ca`.
`/sync` refuse tout type hors `LIVING_RULE_TYPES` (conflit `op_error`).

## Urgence

Modèle déterministe fondé sur `due_date` (il n'y a plus de demi-vie). La `due_date` vaut la
dernière validation plus l'intervalle, ou `created_at` si la tâche n'a jamais été faite.
L'état est :

- **Rouge** : `today > due_date`.
- **Orange** : `due_date - window <= today <= due_date`, avec
  `window = floor(intervalle * 0.10)` (7 j donne 0, 30 j donne 3, 60 j donne 6), plafonnée à
  7 j pour `calendar` et `anniversary` (backend et `TaskUrgencyCalculator.kt`).
- **Vert** : tout le reste.

La `tolerance` (STRICT / NORMAL / SOUPLE) ne change ni l'état ni le « en retard ». Elle sert
seulement de niveau de priorité pour le sélecteur « la plus urgente » de l'accueil : niveau
Strict, puis Normal, puis Souple (en cascade), puis repli sur les Orange à plat. Le tri à
l'intérieur d'un niveau se fait par ratio retard / intervalle décroissant.

## Ordre de synchronisation

Un client qui émet un **nouveau type** d'opération `/sync` exige que le gestionnaire serveur
correspondant soit déployé **avant** l'APK qui l'émet. Sinon le serveur rejette l'opération et
le client reste bloqué dans sa file de nouvelles tentatives. Un ajout se déploie donc serveur
d'abord ; un retrait, APK d'abord.

**Garde-fou de version** : le serveur peut retirer une version d'APK de la circulation
(`MIN_APP_VERSION_CODE`, réponse 426 sur les routes de données, écran terminal côté
application). Il est inactif tant que le seuil vaut 0. Lever un seuil bloque d'un coup tous
les téléphones en dessous, et les APK sont installés hors boutique : l'ordre d'activation
n'est pas négociable. Détail : [`version-gate.fr.md`](version-gate.fr.md).

**Où tourne quoi** : le champ `version` de `/api/health` est une chaîne recopiée à la main ;
elle a menti dans les deux sens. Ce qui identifie le code servi est `code_fingerprint`, un
sha256 des fichiers `.py` exécutés, calculé au démarrage
(`backend/app/utils/code_fingerprint.py`) et recalculable depuis une référence git par
`scripts/fingerprint_of_ref.py`. `bash scripts/deployed_versions.sh` compare les deux pour
chaque environnement.

**Déployer** : la prod se déploie par `gh workflow run deploy.yml -f confirm=deploy` (code de
`main`, se propose sans s'exécuter seul) ; un environnement de test peut recevoir l'arbre de
travail, donc du non commité. Vérifier ce qui tourne, les pièges mesurés et la lecture d'une
trace de l'APK minifié : [`self-hosting.fr.md`](self-hosting.fr.md).

## Appareils

Le JWT n'a pas d'`exp`, et c'est assumé : une application de foyer ne redemande pas un code
toutes les semaines. Ce qui le rend révocable est la table `devices` : chaque appel
authentifié vérifie que l'appareil du jeton n'est pas révoqué.

Deux règles à ne pas casser :

- **Le `device_id` vit dans un DataStore exclu de la sauvegarde automatique d'Android**
  (`res/xml/data_extraction_rules.xml` et `full_backup_content.xml`, les deux ensemble,
  `minSdk` valant 26). Le remettre dans `torchons_prefs` ferait partager une identité à deux
  téléphones issus d'une même sauvegarde, et l'échec est silencieux.
- **L'adoption d'une identité neuve ne vaut que pour un `device_id` inconnu, jamais révoqué**,
  sinon un téléphone coupé se redonne un accès.

Décisions, mesures et pièges : [`appareils.fr.md`](appareils.fr.md).
