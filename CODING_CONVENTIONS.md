# Coding conventions — Torchons & Serviettes

> 🇫🇷 [Lire en français](CODING_CONVENTIONS.fr.md)

Technical reference for contributors. Covers code style, architecture
summary, API surface, recurrence model, urgency model, cache & sync
rules. For project-wide conventions (PR process, commit format), see
[CONTRIBUTING.md](CONTRIBUTING.md).

## Language conventions in code

- **New code (written from now on)**: comments, docstrings and
  identifiers in **English** — for i18n readiness and external
  contributors.
- **Existing French code**: stays as-is, no churn. A bulk migration is
  scoped as a dedicated post-launch chantier.
- **User-facing strings** (anything an end user reads): i18n-ready.
  Android via `res/values/strings.xml` (FR default) +
  `res/values-en/strings.xml` (EN) + `stringResource(R.string.…)`.
  No literal `Text("…")` for user-visible labels. Backend
  `HTTPException(detail=…)` user-visible: use `LocalizedHTTPException`
  with client-side i18n keys (offline-first compatible).

## Architecture

- **Backend** FastAPI (Python): REST API + offline sync + JWT auth
- **Android** Kotlin native: Jetpack Compose + Room + Retrofit
- **DB** PostgreSQL 16: UUIDs everywhere, soft deletes, `change_log`
  table for sync

## Phases (feature rollout)

- **P1** — Task CRUD + recurrence + assignment + categories + offline sync
- **P2** — Gamification (due-date urgency, streaks, leaderboard)
- **P3** — Fair-share (configurable ratio + points per task)
- **P4** — Push notifications (Firebase) + local reminders

## API surface (base `/api`)

| Group | Endpoints |
|--------|-----------|
| Auth | `POST /auth/login`, `GET /auth/me` |
| Tasks | `GET/POST/PUT/DELETE /tasks` |
| Categories | `GET/POST/PUT/DELETE /categories` |
| Completions | `POST /tasks/{id}/complete`, `GET /completions` |
| Sync | `POST /sync` |
| Gamification (P2) | `GET /dashboard`, `/streaks`, `/leaderboard` |
| Fair-share (P3) | `GET/PUT /fair-share/config`, `GET /fair-share/balance` |
| Notifications (P4) | `POST/DELETE /notifications/register` |

## Recurrence (JSONB shape)

```json
{"type": "interval",    "every_n_days": 3}
{"type": "weekdays",    "days": [1, 3, 5]}
{"type": "monthly",     "day_of_month": 1}
{"type": "custom",      "every_n_weeks": 2, "day": 2}
```

## Urgence (since v0.10.0 — freshness rewrite)

Deterministic model based on `due_date` (no more half-life decay).
`due_date = last_completed + interval` (or `created_at` if never done).
States:

- **Red** — `today > due_date`
- **Orange** — `due_date - window <= today <= due_date`, with
  `window = floor(interval * 0.10)` (7d → 0, 30d → 3, 60d → 6)
- **Green** — otherwise

`tolerance` (STRICT / NORMAL / SOUPLE) does not change the state or
the "overdue" flag. It only acts as a priority tier for the
"most urgent" selector on the home screen: Strict tier > Normal tier
> Souple tier (cascade), then fallback to flat Orange. Intra-tier
ordering: `overdue / interval` ratio descending.

## Cache / offline-first (MANDATORY)

Before touching Room cache, refresh, sync or derived state, read
[`docs/cache-conventions.md`](docs/cache-conventions.md) in full.
TL;DR:

1. Derived state is recomputed on **every mutation**: optimistic
   local write + enqueue → recompute immediately, **without network**.
2. Offline = trust the front. Online = server is authoritative.
3. Online refresh = **REPLACE** the scope (`replaceAll` /
   `replaceForTask`), never upsert-only — otherwise ghost rows and
   un-propagated deletions accumulate.

Full patterns, anti-patterns and checklist:
[`docs/cache-conventions.md`](docs/cache-conventions.md).

## Sync ordering

A client emitting a NEW type of `/sync` operation requires the
corresponding server handler to be **deployed BEFORE** the APK
emitting it — otherwise the server rejects the op and the client
gets stuck in its retry queue.
