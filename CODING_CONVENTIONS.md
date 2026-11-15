# Coding conventions — Torchons & Serviettes

> 🇫🇷 [Lire en français](CODING_CONVENTIONS.fr.md)

This document carries what is **its own**: language conventions in the code,
and the phase breakdown. Everything else — architecture, API surface,
recurrence, urgency, cache and sync ordering — lives **elsewhere**, and is not
copied here.

This is not a filing preference. Those sections existed in two places,
reworded, and had drifted apart with nothing to signal it: the Recurrence
section still described four types of which **three were dead**, in a document
that `backend/app/models/task.py` pointed to as the spec for those very
formats. A copied fact does not stay in sync.

For process (PRs, commit format): [CONTRIBUTING.md](CONTRIBUTING.md).

## Where to find what

| Topic | Single source |
|-------|---------------|
| Architecture (backend / Android / DB) | [`docs/architecture.md`](docs/architecture.md) § Architecture |
| API surface | [`docs/architecture.md`](docs/architecture.md) § API |
| Recurrence shapes (5 live types) | [`docs/contexts.md`](docs/contexts.md) § Calendar recurrence, and [`docs/architecture.md`](docs/architecture.md) § Recurrence |
| Urgency model (red / orange / green) | [`docs/architecture.md`](docs/architecture.md) § Urgency |
| Contexts (replacing categories and rooms) | [`docs/contexts.md`](docs/contexts.md) |
| Cache and offline-first | [`docs/cache-conventions.md`](docs/cache-conventions.md) |
| Sync ordering (server before APK) | [`docs/architecture.md`](docs/architecture.md) § Sync ordering |
| Modules and progressive disclosure | [`docs/module-registry.md`](docs/module-registry.md) |

## Language conventions in the code

- **New code (written from now on)**: comments, docstrings and identifiers in
  **English** — to prepare for i18n and ease external contributions.
- **Existing French code**: left as is, no churn. A bulk migration is scoped as
  a dedicated post-launch chantier.
- **User-facing strings**: i18n-ready. Android via `res/values/strings.xml`
  (FR by default) + `res/values-en/strings.xml` (EN) +
  `stringResource(R.string.…)`. No more literal user-visible `Text("…")`.
  Backend user-visible `HTTPException(detail=…)`: use `LocalizedHTTPException`
  with client keys (offline-first compatible).

## Phases (feature rollout)

- **P1** — Task CRUD + recurrence + assignment + contexts + offline sync
- **P2** — Gamification (due-date urgency, streaks, leaderboard)
- **P3** — Fair-share (configurable ratio + points per task)
- **P4** — Push notifications (Firebase) + local reminders
