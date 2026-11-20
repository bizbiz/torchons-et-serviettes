# Conventions cache / offline-first (T&S)

> 🇬🇧 [English version](cache-conventions.md)

> **À lire avant de toucher au cache Room, aux refresh, à la sync ou à tout
> état dérivé (urgence, échéance, retard, points).** Issu d'un bug réel
> (suppression de complétion non propagée → `lastCompletedAt` périmé →
> tâche affichée « dans 6j » alors que très en retard).

## TL;DR — 3 règles

1. **État dérivé recalculé à chaque mutation.** Toute écriture qui change
   les données sources d'un calcul (ajout/suppression/édition d'une
   complétion → échéance/urgence/retard de la tâche) doit mettre le cache
   local dans un état tel que le recalcul (observe-flow) ressort juste
   **immédiatement**, sans attendre le réseau.
2. **Offline = on fait confiance au front. Online = le serveur est
   autoritaire.** Hors-ligne : écriture locale optimiste + recalcul local.
   En ligne : on récupère/réconcilie les données serveur et elles
   priment.
3. **Un refresh en ligne REMPLACE son scope, jamais un upsert-only.** Un
   `upsert` ne supprime pas les lignes absentes de la réponse serveur →
   les suppressions/edits serveur ne se propagent pas → cache fantôme.
   Refresh plein → `replaceAll`. Refresh scoppé (par tâche) →
   `replaceForTask`. (`upsert-only` réservé aux scopes sans suppression
   possible.)

## Patterns obligatoires

### Mutation offline-first (créer / supprimer / éditer)

Mirror de `TaskRepository.completeTask` / `deleteCompletion` /
`deleteTask` :

```
1. Écriture LOCALE optimiste (DAO upsert/deleteById)  -> UI/recalcul instant
2. enqueue(clientOpId, table, op, rowId, payload|null) -> file offline
3. (la sync push l'op ; SyncWorker applique les change_log serveur,
   y compris les DELETE -> completionDao.deleteById)
```

Les étapes 1 et 2 forment UNE `LocalMutation` (`data/repository/LocalMutation.kt`) : l'écriture
locale et l'op dans une seule transaction Room, exécutée dans le scope applicatif,
`requestSync()` après le commit. ❌ Jamais deux étapes suspend dans le scope de l'appelant :
quitter l'écran entre les deux montre un changement jamais mis en file (le prochain refresh
l'efface). Nouvelle mutation = un ledger comme `TaskLedger`, dont le bloc appelle `enqueue(...)`
sur le `MutationScope`.

❌ **Anti-pattern** : appeler un endpoint REST online-only (ex.
`apiService.deleteCompletion`) sans delete local optimiste ni enqueue →
casse l'offline ET laisse une ligne fantôme en cache (upsert ne la retire
pas).

❌ **Ne PAS** déclencher un `refresh<Scope>()` juste après une mutation
optimiste tant que l'op n'est pas poussée : le refetch serveur
ré-ajouterait la ligne (le serveur l'a encore). Laisser la sync
réconcilier. L'Accueil se rafraîchit quand le worker a fini un push
(`lastSyncPushAt`), pas après le tap.

✅ **Une complétion solo s'écrit au tap**, jamais après la célébration :
`CompletionLedger` (via `TaskRepository.completeTask`) fait contrôle de
doublon + ligne Room + op en file dans UNE transaction, dans un scope
applicatif que quitter l'écran n'annule pas (19/11 : quatre taps, une seule
op arrivée au serveur). La célébration est visuelle ; un second tap sur la
même tâche pendant celle-ci est ignoré (`TapGuard`). Un `applied` du serveur
marqué `dropped` supprime la ligne locale (`SyncWorker`).

✅ **« Fait ensemble » démarre aussi au tap**, et tout son déroulé (lignes
optimistes → appel → réconciliation ou rollback) tourne dans le scope
applicatif (`CollectiveCompletionLedger`) : l'écran n'attend que le résultat
pour son message. Il reste en ligne (endpoint dédié, le serveur répartit les
points), il ne passe pas par `/sync`.

### Refresh / réconciliation (online = autoritaire)

- Plein (`taskId==null && userId==null`) → `replaceAll`.
- Par tâche (`taskId!=null`) → `replaceForTask(taskId, …)`.
- ❌ Un refresh de complétions ne s'applique jamais tel quel : il passe par
  `CompletionRefreshGuard.fetchThenApply`. Une réponse partie AVANT un push et
  appliquée APRÈS le règlement de l'op effacerait la ligne qu'on vient de
  pousser (l'op n'est plus en file, `replaceAll` ne la protège plus). Le worker
  bumpe une époque quand il règle des ops de complétion (`settleApplied`) ; le
  refresh la lit avant sa requête et jette sa réponse si elle a bougé (une
  seconde requête, puis rien). Le verrou n'entoure que du travail local.
- ❌ Idem pour les **tâches** : `TaskDao.replaceAll` épargne les lignes dont une
  op est encore en file (INSERT, UPDATE, DELETE), et `refreshTasks` passe par
  `refreshGuard.tasks` (`refreshTasksCache`), une seconde époque que
  `settleApplied` et `applyServerChanges` bumpent pour la table `tasks`. Sans
  cela une tâche créée hors ligne disparaissait au rafraîchissement suivant.
- **Changement de foyer** : une seule voie, `HouseholdSwitcher.switchWith`
  (jeton, vidage de TOUTES les tables sauf `pending_operations`, rafraîchissement),
  dans le scope applicatif. Jamais de vidage partiel dans un ViewModel. Les ops
  en file gardent leur `foyerId` et partent vers leur foyer d'origine ; si
  l'utilisateur l'a quitté, le serveur répond `out_of_scope`, l'op est retirée
  sans avis.
- Le backend expose les valeurs autoritaires utiles (ex.
  `TaskResponse.last_completed_at` calculé serveur). **C'est câblé** :
  `Task` DTO + `TaskEntity.lastCompletedAt` (migration Room 9→10) →
  `TaskUrgencyCalculator.latestCompletedAt(server, local)` réconcilie par
  `max()` (online = serveur autoritaire ; offline = complétion optimiste
  locale plus récente gagne) → `TaskListViewModel`. Cf
  `backend/app/api/tasks.py:_task_to_response`.

  ❌ **Anti-pattern (bug réel « Aspirateur dans 6j »)** : alimenter
  `computeDueDate` uniquement avec le `max` du cache Room des complétions.
  Si ce cache est vide pour la tâche (ex. écran liste rafraîchi via
  `/tasks` sans `/completions`, ou Home dont le refresh n'appelle pas
  `refreshCompletions`), `computeDueDate` retombe sur `created_at` →
  échéance = `created_at + N` → « dans Nj » au lieu de Rouge. Toujours
  passer par `latestCompletedAt(task.lastCompletedAt, cacheMax)`.

### Typage des champs numériques JSONB (recurrence_rule)

Le client Android (Gson) parse les champs numériques de la règle (`every_n_days`,
`every_n_months`, `months`, `day`, `grace_days`) comme des **Int stricts**. Les types vivants
sont listés dans [`architecture.fr.md`](architecture.fr.md) § Récurrence. Un float persisté en
JSONB (`7.0`) casse le parsing → recurrence ignorée → urgence fausse
(« dans 6j » au lieu de Rouge). Le chemin REST passe par Pydantic
(`every_n_days: int`, coercé), **mais `/sync` bypass Pydantic** et stocke
`op.data` brut.

➡️ **Règle : toute écriture serveur de `recurrence_rule` issue du client
passe par `normalize_recurrence_rule()`** (`backend/app/services/recurrence.py`) qui
force les champs numériques en int. Appliqué dans `sync.py:_handle_task`
(INSERT + UPDATE). ❌ Ne jamais faire `setattr(row, "recurrence_rule",
op.data[...])` brut. Tout nouveau chemin d'écriture (import catalogue,
migration, script) qui pose un `recurrence_rule` doit passer par ce helper
ou garantir des int.

### État dérivé

- `TaskEntity.lastCompletedAt` ne stocke que la valeur **calculée par le serveur**, rafraîchie
  à chaque `GET /api/tasks`. La date effective utilisée pour l'urgence n'est stockée nulle
  part : elle est dérivée à chaque émission de flow par `latestCompletedAt(serveur, cache)`,
  c'est-à-dire le `max()` de cette valeur et du cache des complétions. Donc toute correction
  d'urgence passe par la **justesse du cache des complétions** (c'est lui qui porte la
  complétion optimiste hors ligne), pas par un champ dénormalisé.
- Les écrans *live* (Home `/api/suggestions`, `/api/retard`, Stats) sont
  calculés serveur et ne dépendent pas du cache Room. Les écrans
  offline-first (liste des tâches, détail) dépendent du cache → ce sont
  eux qui exigent ces règles.

### Écritures sans ligne locale : ops REST en file, réglages du membre, gestes en ligne seulement

Trois sortes d'écriture, et un écran doit savoir laquelle (audit du 20/11/2026, D3/D6/D7/D9).

1. **Écritures idempotentes « fixer ma valeur » avec une route REST** (votes de fréquence,
   d'effort et d'affinité, réponse à un prompt) : `VoteRepository` / `sendOrQueue` envoient
   d'abord en ligne ; une erreur réseau, un 5xx, un 408/429, ou une tâche encore en attente dans
   la file mettent l'écriture dans `pending_operations` (tables dans `core/sync/RestOps.kt`), et
   le worker la rejoue en REST APRÈS les lots `/sync`. Un 4xx permanent est l'erreur de
   l'appelant, montrée, jamais mise en file. Ne mettre en file qu'une écriture que le serveur
   traite pareil deux fois (vérifier la route d'abord). Un prompt dont la réponse est en file est
   ignoré par `fetchNextPrompt` (`VoteRepository.promptsAnsweredOffline`).
   Ces votes n'ont pas de ligne Room : l'écran qui les montre pose la valeur EN FILE par-dessus la
   dernière réponse du serveur (`overlayQueuedVotes`, alimenté par
   `VoteRepository.observeQueuedVotes`) : le détail d'une tâche montre le vote qu'on vient de
   faire hors ligne, et son bandeau « tu n'as pas voté » suit. Quand la file se vide, l'écran
   recharge AVANT de retirer la surcouche (pas de retour en arrière furtif).
2. **Réglages du membre** (`user_settings`, table `/sync`) : `SettingsLedger` met l'UPDATE en
   file (`LocalMutation`), l'écran et le DataStore changent tout de suite, et un rechargement
   applique les ops en file par-dessus la réponse du serveur (`overlayPendingSettings`). Les
   nombres du payload gardent leur type (`opPayload`) : `/sync` écrit `user_settings` par un
   `setattr` brut.
3. **Gestes en ligne seulement** (nom du foyer, gel maison, noms Wi-Fi, seuils bonus, objectifs,
   contextes, jours de présence...) : le contrôle est DÉSACTIVÉ hors ligne avec la ligne
   « réseau nécessaire » (`LocalIsOnline`, `NetworkNeededNote`), et la requête passe par
   `runOnline` : une exception ou une réponse non 2xx annule le changement optimiste et rend
   un `UserError` traduit que l'écran montre.
   ❌ `catch (_: Exception) {}`, ou un `isSuccessful` que personne ne lit.
   `LocalIsOnline` bascule au premier appel raté : le premier tap après la perte du réseau
   reçoit l'erreur, les suivants le bouton désactivé.
   ❌ La note ne doit pas bouger ce qui est sous le doigt : sur un écran ou une carte avec du
   contenu à toucher dessous, `NetworkNeededSlot` (ligne toujours posée), pas `NetworkNeededNote`
   (Accueil : bouton grisé + toast au tap). Un écran qui montre une erreur de chargement utilise
   aussi `ReloadOnReconnect` (recharge une fois quand `LocalIsOnline` passe de faux à vrai), pas
   seulement un bouton « Réessayer ».

Passages de synchro : `SyncTrigger.requestSync` n'annule jamais un passage EN COURS (une requête
de vote déjà chez le serveur serait rejouée, 500) : `syncRequestAction` en chaîne un nouveau
derrière lui, et remplace un passage qui ne fait qu'attendre (inactif, backoff). `syncPassLock`
sérialise les workers one-shot et périodique dans le processus.

Le pull est paginé : `/sync` répond `has_more` quand il a coupé à 500 lignes, et le worker
redemande (au plus `MAX_PULL_PAGES` par passage). « Me déconnecter et couper cet appareil »
efface la file : sa confirmation montre le nombre et propose de synchroniser d'abord
(`syncBeforeSignOut`).

## Checklist nouvelle feature touchant cache/sync

- [ ] Mutation : écriture locale optimiste + enqueue (offline OK) ?
- [ ] Le calcul dérivé impacté ressort juste **sans réseau** après la
      mutation ?
- [ ] Refresh online = `replace` du scope (pas upsert-only) ?
- [ ] DELETE serveur propagé au DAO par `SyncWorker` ?
- [ ] Pas de `refresh` immédiat qui ré-ajoute une ligne optimistiquement
      supprimée ?
- [ ] Testé **en mode avion** ET reconnexion (la sync réconcilie) ?
- [ ] Nouveau **type d'op `/sync`** (table/opération offline jamais
      émise avant) ? → handler serveur déployé **AVANT** l'APK qui
      l'émet (cf ci-dessous).

## Ordre de déploiement client ⇄ serveur (bug réel, mai 2026)

Client et serveur se déploient séparément. Si un changement client
**émet un nouveau type d'op `/sync`** (ex. passage de `task_completions`
INSERT/DELETE à un **UPDATE** pour l'édition de complétion), le **handler
serveur correspondant doit être en prod AVANT** que l'APK qui l'émet ne
soit utilisé.

Pourquoi c'est grave : la boucle `/sync` ne catchait que
`StaleUpdateError`/`IntegrityError`. Une op qu'un vieux handler ne
supporte pas levait une `HTTPException` **non catchée** → le **batch
`/sync` entier** renvoyait 400, ne committait jamais, et l'op était
re-tentée à l'infini → **toute la file offline du foyer bloquée** (pas
juste la feature concernée).

Filet en place (`backend/app/api/sync.py`) : un `except Exception`
isole désormais une op inattendue en **conflit par-op** (rollback +
`SyncConflict`), le reste du batch passe. C'est un backstop, **pas** une
dispense : garder l'ordre serveur-d'abord.
