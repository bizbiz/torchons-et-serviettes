# Conventions cache / offline-first (T&S)

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
réconcilier.

### Refresh / réconciliation (online = autoritaire)

- Plein (`taskId==null && userId==null`) → `replaceAll`.
- Par tâche (`taskId!=null`) → `replaceForTask(taskId, …)`.
- Le backend expose les valeurs autoritaires utiles (ex.
  `TaskResponse.last_completed_at` calculé serveur) ; le client peut s'en
  servir comme source de vérité online, sinon il dérive du cache local
  (offline). Cf `backend/app/api/tasks.py:_task_to_response`.

### État dérivé

- `lastCompletedAt` n'est **pas** stocké sur `TaskEntity` : il est dérivé
  du cache des complétions à chaque émission de flow. Donc toute
  correction d'urgence passe par la **justesse du cache des complétions**,
  pas par un champ dénormalisé.
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
