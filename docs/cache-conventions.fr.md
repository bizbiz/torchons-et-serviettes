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

### Refresh / réconciliation (online = autoritaire)

- Plein (`taskId==null && userId==null`) → `replaceAll`.
- Par tâche (`taskId!=null`) → `replaceForTask(taskId, …)`.
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
