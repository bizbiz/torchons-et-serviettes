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

❌ **Anti-pattern**: calling an online-only REST endpoint (e.g.
`apiService.deleteCompletion`) without an optimistic local delete and
enqueue → breaks offline AND leaves a ghost row in the cache (upsert
never removes it).

❌ **Do NOT** trigger a `refresh<Scope>()` right after an optimistic
mutation while the op has not been pushed yet: the server refetch would
re-add the row (the server still has it). Let sync reconcile.

### Refresh / reconciliation (online = authoritative)

- Full (`taskId==null && userId==null`) → `replaceAll`.
- Per task (`taskId!=null`) → `replaceForTask(taskId, …)`.
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

The Android client (Gson) parses `every_n_days` (and `day_of_month`,
`every_n_weeks`, `days[]`) as **strict Ints**. A float persisted in JSONB
(`7.0`) breaks parsing → recurrence ignored → wrong urgency ("due in 6d"
instead of Red). The REST path goes through Pydantic
(`every_n_days: int`, coerced), **but `/sync` bypasses Pydantic** and
stores raw `op.data`.

➡️ **Rule: every server-side write of a client-provided
`recurrence_rule` goes through `normalize_recurrence_rule()`**
(`app/services/recurrence.py`), which forces numeric fields to int.
Applied in `sync.py:_handle_task` (INSERT + UPDATE). ❌ Never do
`setattr(row, "recurrence_rule", op.data[...])` raw. Any new server-side
write path (catalog import, migration, script) that sets a
`recurrence_rule` must go through this helper or guarantee ints.

### Derived state

- `lastCompletedAt` is **not** stored on `TaskEntity`: it is derived from
  the completions cache on every flow emission. So any urgency fix goes
  through the **correctness of the completions cache**, not a
  denormalized field.
- *Live* screens (Home `/api/suggestions`, `/api/retard`, Stats) are
  computed server-side and do not depend on the Room cache. Offline-first
  screens (task list, detail) depend on the cache → they are the ones
  these rules exist for.

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
