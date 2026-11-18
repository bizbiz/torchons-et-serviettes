# Architecture — overview of Torchons & Serviettes

French version: [`architecture.fr.md`](architecture.fr.md).

Torchons & Serviettes is a collaborative household-chores app for a household of two or more
members, with gamification and fair-share. This document is the map: the layers, the API
surface, the recurrence and urgency rules, the client/server deployment order and the device
model. Each section points to the detail.

## Architecture

- **Backend** FastAPI (Python): REST API, offline sync and JWT authentication.
- **Android** native Kotlin: Jetpack Compose, Room and Retrofit.
- **Database** PostgreSQL 16: UUIDs everywhere, soft deletes and a `change_log` for sync.
- **Contexts**: a task carries several of them. They replaced categories and rooms, which
  were dropped from the database. Visibility rules, two flags and suggestions:
  [`contexts.md`](contexts.md).
- **Solo task** (a single assignee): visible to that person only, whoever created it. Every
  route or `/sync` operation that reads or writes a task, or a row pointing to one, goes
  through `visible_tasks` / `visible_to` (`backend/app/utils/scoping.py`);
  `tests/test_solo_task_privacy.py` sweeps every GET route.
- **Completion notifications**: through REST (`send_completion_event`) and through `/sync`
  (`send_sync_completions`, one per member and per call). The app validates through `/sync`.
- **"To do today"** (delegations): a member hands a task to other members who see it. Written
  through `/sync` (table `task_delegations`: `INSERT` `{"task_id", "recipient_ids"}`, `DELETE`
  to cancel, author only), read by `GET /delegations`; any completion dated on or after its
  day settles it. Grouped notification after 5 quiet minutes and reminders at 12/16/20 h
  Paris time, state kept in the database (`services/delegation_push.py`). Household switch:
  the `today` module, on by default. Spec:
  [`plans/a-faire-aujourdhui.md`](plans/a-faire-aujourdhui.md).
- **Cache and offline**: the app is offline-first; the rules for the Room cache, refresh and
  derived state are in [`cache-conventions.md`](cache-conventions.md).
- **Modules and progressive discovery**: a two-level registry (core and modules), described
  in [`module-registry.md`](module-registry.md).

## API

Base: `/api`.

| Group | Routes |
|-------|--------|
| Auth | `GET /auth/me`, `/auth/users` — tokens issued by `/onboarding`, `/pairing`, `/recovery` |
| Tasks | `GET/POST/PUT/DELETE /tasks` |
| Contexts | `GET /catalog/contexts` (categories and rooms: legacy shim) |
| Completions | `POST /tasks/{id}/complete`, `GET /completions` |
| Sync | `POST /sync` (`tasks`, `task_completions`, `user_settings`, `task_delegations`) |
| To do today | `GET /delegations` (writes through `/sync`) |
| Gamification | `GET /leaderboard`, `/streaks`, `/stats/*` |
| Fair-share | `GET /fair-share/status`, `/fair-share/history`, `GET/PUT /fair-share/config` |
| Notifications | `POST/DELETE /notifications/register` |
| Devices | `GET /devices`, `POST /devices/register`, `/devices/{id}/revoke` |

## Recurrence

A task's recurrence is a JSONB object. Five types are live:

- `{"type":"interval","every_n_days":3}`
- `{"type":"monthly_variable","days_per_month":{"1":30,...}}` — 12 months, 1 to 365 days.
- `{"type":"uncertain"}` — inferred cadence, no due date shown.
- `{"type":"calendar","months":[5],"day":15,"grace_days":0}` — anchored on the calendar, not
  on the last completion: no drift. See [`contexts.md`](contexts.md) § Calendar recurrence.
- `{"type":"anniversary","every_n_months":12}` — anchored on the first living completion
  (`first_completed_at`), same section. The list of types is `LIVING_RULE_TYPES` on the
  backend and `RecurrenceTypes.LIVING` in Kotlin, guarded by a test.

On normalization, `weekdays` and `custom` become `interval`, and `monthly` becomes `calendar`
(12 months). Old rows were converted by migration `d4ec241658ca`. `/sync` refuses any type
outside `LIVING_RULE_TYPES` (an `op_error` conflict).

## Urgency

A deterministic model based on `due_date` (there is no half-life any more). `due_date` is the
last completion plus the interval, or `created_at` if the task was never done. The state is:

- **Red**: `today > due_date`.
- **Orange**: `due_date - window <= today <= due_date`, with `window = floor(interval * 0.10)`
  (7 d gives 0, 30 d gives 3, 60 d gives 6), capped at 7 d for `calendar` and `anniversary`
  (backend and `TaskUrgencyCalculator.kt`).
- **Green**: everything else.

`tolerance` (STRICT / NORMAL / SOUPLE) changes neither the state nor "overdue". It only acts
as a priority tier for the home screen's "most urgent" picker: Strict tier, then Normal, then
Souple (cascade), then a fallback on flat Orange tasks. Sorting inside a tier is by
lateness / interval ratio, descending.

## Sync ordering

A client that emits a **new type** of `/sync` operation requires the matching server handler
to be deployed **before** the APK that emits it. Otherwise the server rejects the operation
and the client stays stuck in its retry queue. An addition therefore ships server first; a
removal ships the APK first.

**Version gate**: the server can pull an APK version out of circulation
(`MIN_APP_VERSION_CODE`, a 426 response on data routes, a terminal screen in the app). It is
inactive while the threshold is 0. Raising a threshold blocks every phone below it at once,
and APKs are sideloaded: the activation order is not negotiable.
Detail: [`version-gate.md`](version-gate.md).

**What runs where**: the `version` field of `/api/health` is a hand-copied string; it has
lied in both directions. What identifies the served code is `code_fingerprint`, a sha256 of
the executed `.py` files, computed at boot (`backend/app/utils/code_fingerprint.py`) and
recomputable from a git ref with `scripts/fingerprint_of_ref.py`.
`bash scripts/deployed_versions.sh` compares the two for each environment.

**Deploying**: production is deployed with `gh workflow run deploy.yml -f confirm=deploy` (code
of `main`, proposed, never run on its own); a test environment may receive the working tree, so
uncommitted code runs. Verifying what runs, measured pitfalls and reading a trace from the
minified APK: [`self-hosting.md`](self-hosting.md).

## Devices

The JWT has no `exp`, and that is deliberate: a household app does not ask for a code every
week. What makes it revocable is the `devices` table: every authenticated call checks that the
token's device is not revoked.

Two rules not to break:

- **`device_id` lives in a DataStore excluded from Android Auto Backup**
  (`res/xml/data_extraction_rules.xml` and `full_backup_content.xml`, both together, `minSdk`
  being 26). Putting it back in `torchons_prefs` would make two phones restored from the same
  backup share one identity, and the failure is silent.
- **Adopting a fresh identity only applies to an UNKNOWN `device_id`, never a revoked one**,
  otherwise a cut-off phone would hand itself access again.

Decisions, measurements and pitfalls: [`appareils.md`](appareils.md).
