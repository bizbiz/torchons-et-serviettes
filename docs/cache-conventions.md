# Cache / offline-first conventions (T&S)

> 🇫🇷 [Version française](cache-conventions.fr.md)

> **Read this before touching the Room cache, refreshes, sync, or any
> derived state (urgency, due date, lateness, points).** Born from a real
> bug (a completion deletion that never propagated → stale
> `lastCompletedAt` → a task shown as "due in 6d" while badly overdue).

## TL;DR — 3 rules

1. **Derived state is recomputed on every mutation.** Any write that
   changes the source data of a computation (adding/deleting/editing a
   completion → the task's due date/urgency/lateness) must leave the local
   cache in a state where the recomputation (observe-flow) comes out right
   **immediately**, without waiting for the network.
2. **Offline = trust the client. Online = the server is authoritative.**
   Offline: optimistic local write + local recompute. Online: fetch and
   reconcile server data, and it wins.
3. **An online refresh REPLACES its scope, never upsert-only.** An
   `upsert` does not remove rows absent from the server response →
   server-side deletions/edits never propagate → ghost cache. Full
   refresh → `replaceAll`. Scoped refresh (per task) → `replaceForTask`.
   (`upsert-only` is reserved for scopes where deletion is impossible.)

## Mandatory patterns

### Offline-first mutation (create / delete / edit)

Mirror of `TaskRepository.completeTask` / `deleteCompletion` /
`deleteTask`:

```
1. Optimistic LOCAL write (DAO upsert/deleteById)   -> instant UI/recompute
2. enqueue(clientOpId, table, op, rowId, payload|null) -> offline queue
3. (sync pushes the op; SyncWorker applies server change_log entries,
   including DELETEs -> completionDao.deleteById)
```

Steps 1 and 2 are ONE `LocalMutation` (`data/repository/LocalMutation.kt`): the local
write and the op in a single Room transaction, run in the application scope, `requestSync()`
after the commit. ❌ Never write them as two suspend steps in the caller's scope: leaving the
screen between them shows a change that is never queued (the next refresh erases it). New
mutation = a ledger like `TaskLedger`, its block calling `enqueue(...)` on the `MutationScope`.

❌ **Anti-pattern**: calling an online-only REST endpoint (e.g.
`apiService.deleteCompletion`) without an optimistic local delete and
enqueue → breaks offline AND leaves a ghost row in the cache (upsert
never removes it).

❌ **Do NOT** trigger a `refresh<Scope>()` right after an optimistic
mutation while the op has not been pushed yet: the server refetch would
re-add the row (the server still has it). Let sync reconcile. Home
refreshes when the worker has finished a push (`lastSyncPushAt`), not
after the tap.

✅ **A solo completion is written at the tap**, never after the
celebration: `CompletionLedger` (via `TaskRepository.completeTask`) does
duplicate check + Room row + queued op in ONE transaction, in an
application scope that leaving the screen does not cancel (Nov 19: four
taps, a single op reached the server). The celebration is visual only; a
second tap on the same task during it is ignored (`TapGuard`). A server
`applied` entry flagged `dropped` deletes the local row (`SyncWorker`).

✅ **« Done together » also starts at the tap**, and its whole sequence
(optimistic rows → call → reconcile or rollback) runs in the application
scope (`CollectiveCompletionLedger`): the screen only waits for the result
for its message. It stays online (dedicated endpoint, the server splits the
points), it does not go through `/sync`.

### Refresh / reconciliation (online = authoritative)

- Full (`taskId==null && userId==null`) → `replaceAll`.
- Per task (`taskId!=null`) → `replaceForTask(taskId, …)`.
- ❌ A completions refresh is never applied as is: it goes through
  `CompletionRefreshGuard.fetchThenApply`. An answer sent BEFORE a push and
  applied AFTER its op was settled would erase the row just pushed (the op is
  no longer queued, `replaceAll` no longer protects it). The worker bumps an
  epoch when it settles completion ops (`settleApplied`); the refresh reads it
  before its request and drops its answer if it moved (one more request, then
  nothing). The lock only ever wraps local work.
- ❌ Same for **tasks**: `TaskDao.replaceAll` spares rows that still have a queued op (INSERT,
  UPDATE, DELETE), and `refreshTasks` goes through `refreshGuard.tasks` (`refreshTasksCache`), a
  second epoch that `settleApplied` and `applyServerChanges` bump for the `tasks` table. Without
  it a task created offline vanished at the next refresh.
- **Household switch**: one path, `HouseholdSwitcher.switchWith` (token, wipe of EVERY table but
  `pending_operations`, refresh), in the application scope. Never a partial wipe in a ViewModel.
  Queued ops keep their `foyerId` and are sent to their original household; if the user left it,
  the server answers `out_of_scope` and the op is dropped without a notice.
- The backend exposes the useful authoritative values (e.g.
  `TaskResponse.last_completed_at` computed server-side). **This is
  wired**: `Task` DTO + `TaskEntity.lastCompletedAt` (Room migration
  9→10) → `TaskUrgencyCalculator.latestCompletedAt(server, local)`
  reconciles via `max()` (online = server authoritative; offline = a more
  recent local optimistic completion wins) → `TaskListViewModel`. See
  `backend/app/api/tasks.py:_task_to_response`.

  ❌ **Anti-pattern (real "Vacuum due in 6d" bug)**: feeding
  `computeDueDate` only with the `max` of the Room completions cache. If
  that cache is empty for the task (e.g. list screen refreshed via
  `/tasks` without `/completions`, or Home whose refresh does not call
  `refreshCompletions`), `computeDueDate` falls back to `created_at` →
  due date = `created_at + N` → "due in Nd" instead of Red. Always go
  through `latestCompletedAt(task.lastCompletedAt, cacheMax)`.

### Typing of numeric JSONB fields (recurrence_rule)

The Android client (Gson) parses the numeric fields of the rule (`every_n_days`,
`every_n_months`, `months`, `day`, `grace_days`) as **strict Ints**. The living types are
listed in [`architecture.md`](architecture.md) § Recurrence. A float persisted in JSONB
(`7.0`) breaks parsing → recurrence ignored → wrong urgency ("due in 6d"
instead of Red). The REST path goes through Pydantic
(`every_n_days: int`, coerced), **but `/sync` bypasses Pydantic** and
stores raw `op.data`.

➡️ **Rule: every server-side write of a client-provided
`recurrence_rule` goes through `normalize_recurrence_rule()`**
(`backend/app/services/recurrence.py`), which forces numeric fields to int.
Applied in `sync.py:_handle_task` (INSERT + UPDATE). ❌ Never do
`setattr(row, "recurrence_rule", op.data[...])` raw. Any new server-side
write path (catalog import, migration, script) that sets a
`recurrence_rule` must go through this helper or guarantee ints.

### Derived state

- `TaskEntity.lastCompletedAt` stores only the **server-computed** value, refreshed on every
  `GET /api/tasks`. The effective date used for urgency is stored nowhere: it is derived on
  every flow emission by `latestCompletedAt(server, cache)`, that is the `max()` of that value
  and the completions cache. So any urgency fix goes through the **correctness of the
  completions cache** (it carries the optimistic offline completion), not a denormalized field.
- *Live* screens (Home `/api/suggestions`, `/api/retard`, Stats) are
  computed server-side and do not depend on the Room cache. Offline-first
  screens (task list, detail) depend on the cache → they are the ones
  these rules exist for.

### Writes with no local row: queued REST ops, member settings, online-only gestures

Three kinds of write, and a screen must know which one it is (2026-11-20 audit, D3/D6/D7/D9).

1. **Idempotent "set my value" writes with a REST route** (frequency, effort and affinity votes,
   the answer to a prompt): `VoteRepository` / `sendOrQueue` send online first; a network error, a
   5xx, a 408/429, or a task still waiting in the queue puts the write in `pending_operations`
   (tables in `core/sync/RestOps.kt`), and the worker replays it over REST AFTER the `/sync`
   batches. A permanent 4xx is the caller's error, shown, never queued. Only queue a write that
   the server answers the same way twice (verify the route first). A prompt whose answer is
   queued is skipped by `fetchNextPrompt` (`VoteRepository.promptsAnsweredOffline`).
   These votes have no Room row, so the screen that shows them lays the QUEUED value over the
   server's last answer (`overlayQueuedVotes`, fed by `VoteRepository.observeQueuedVotes`): the
   task detail shows the vote just saved offline, and its "you have not voted" banner follows.
   When the queue drains the screen refetches BEFORE dropping the overlay (no flicker back).
   The server's last answer is itself cached (`TaskVotesCache`, in `view_snapshots`: the task's
   votes at each fetch, and the user's own affinity on every task, one `GET /my-affinities` made
   with the tasks refresh), so a task opened offline shows the votes the app already knew. What is
   unknown is said so: the rating dialog never claims "Jamais noté" when the server could not be
   asked (`previousRatingLine`).
2. **Member settings** (`user_settings`, a `/sync` table): `SettingsLedger` queues the UPDATE
   (`LocalMutation`), the screen and the DataStore change at once, and a reload applies the queued
   ops over the server's answer (`overlayPendingSettings`). Queued payload numbers keep their
   type (`opPayload`): `/sync` writes `user_settings` with a raw `setattr`.
3. **Online-only gestures** (household name, home freeze, Wi-Fi names, bonus thresholds,
   objectives, contexts, presence days...): the control is DISABLED offline with the
   "network needed" line (`LocalIsOnline`, `NetworkNeededNote`), and the request goes through
   `runOnline`: an exception or a non-2xx answer rolls the optimistic change back and returns a
   translated `UserError` the screen shows.
   ❌ `catch (_: Exception) {}`, or an `isSuccessful` nobody reads.
   `LocalIsOnline` flips on the first failed call, so the first tap after losing the network gets
   the error, the next ones the disabled button.
   ❌ The note must not move what is under the finger: on a screen or card with tappable content
   below it use `NetworkNeededSlot` (line always laid out), not `NetworkNeededNote` (Home: a
   dimmed button + toast on tap). A screen that shows a load error also uses `ReloadOnReconnect`
   (reload once when `LocalIsOnline` goes false → true), never only a "Retry" button.

❌ **An empty list is not an empty answer.** A screen says "Aucun ..." only when its load answered
(`shouldShowEmptyState(isLoading, loadFailed, isEmpty)`): while loading or after a failure it
shows the error (or "unavailable"), never the empty-state text. Edits: the edit form sends only
the fields the user changed against what it loaded (`TaskEditBaseline`, `saveAction`), so a form
opened on stale data cannot revert what it never showed; a save with no change queues nothing.
A task change pulled from `/sync` resolves `context_ids` from the cached contexts
(`taskEntityFromChange`).

Every sign-out goes through `SignOutFlow` (pending count, "Sync first", confirmation): no screen
calls `AuthRepository.logout` or `wipeNow` on its own (`SignOutFlowTest` scans the sources).

Sync passes: `SyncTrigger.requestSync` never cancels a RUNNING pass (a vote request already at the
server would be replayed, 500): `syncRequestAction` chains a new pass behind it, and replaces a
pass that is only waiting (idle, backoff). `syncPassLock` serialises the one-shot and periodic
workers in the process.

The pull is paged: `/sync` answers `has_more` when it cut at 500 rows, and the worker asks again
(at most `MAX_PULL_PAGES` per run). "Sign out and cut this device" wipes the queue: its dialog
shows the count and offers to sync first (`syncBeforeSignOut`).

## Checklist for any new feature touching cache/sync

- [ ] Mutation: optimistic local write + enqueue (offline OK)?
- [ ] Does the impacted derived computation come out right **without
      network** after the mutation?
- [ ] Online refresh = `replace` of the scope (not upsert-only)?
- [ ] Server DELETE propagated to the DAO by `SyncWorker`?
- [ ] No immediate `refresh` that re-adds an optimistically deleted row?
- [ ] Tested **in airplane mode** AND on reconnection (sync reconciles)?
- [ ] New **`/sync` op type** (table/operation never emitted before)? →
      server handler deployed **BEFORE** the APK that emits it (see
      below).

## Client ⇄ server deployment ordering (real bug, May 2026)

Client and server deploy separately. If a client change **emits a new
`/sync` op type** (e.g. moving completion edits from `task_completions`
INSERT/DELETE to an **UPDATE**), the **matching server handler must be in
production BEFORE** the APK that emits it is used.

Why it matters: the `/sync` loop only caught
`StaleUpdateError`/`IntegrityError`. An op an old handler did not support
raised an uncaught `HTTPException` → the **entire `/sync` batch**
returned 400, never committed, and the op was retried forever → **the
household's whole offline queue blocked** (not just the feature at
fault).

Backstop in place (`backend/app/api/sync.py`): an `except Exception` now
isolates an unexpected op as a **per-op conflict** (rollback +
`SyncConflict`), and the rest of the batch goes through. It is a
backstop, **not** a waiver: keep the server-first ordering.
