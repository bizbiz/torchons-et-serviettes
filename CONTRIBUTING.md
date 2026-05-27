# Contributing to Torchons & Serviettes

> 🇫🇷 [Lire en français](CONTRIBUTING.fr.md)

Thanks for your interest in the project. It started as a solo project,
currently in public-launch preparation — external contributions are
welcome but review pace depends on maintainer availability.

## Before opening a PR

- **Discuss first** for substantial changes (new feature, screen
  refactor, model change): open an issue describing what you want to
  do, we align before you code. Small fixes / typos can go straight
  to PR without prior discussion.
- **One PR = one topic.** Avoid mixing a bug fix + a refactor + a new
  feature in the same PR.
- **Target the `main` branch.** No `develop`/`staging` branch —
  everything lands on `main` after review.

## Code style

- **Backend**: type hints everywhere, async SQLAlchemy 2 (no blocking
  session). **New code: comments, docstrings and identifiers in
  English.** Existing French code: stays as-is (no churn).
- **Android**: idiomatic Kotlin, Compose for any new UI (no XML View
  unless specific case). One `@Composable` per file when it exceeds
  100 lines. **New code: comments, docstrings and identifiers in
  English.** Existing French code: stays as-is.
- **User-facing strings**: i18n via `res/values/strings.xml` (FR) +
  `res/values-en/strings.xml` (EN) on Android, and
  `LocalizedHTTPException` on the backend. No untranslated literals
  visible to users. See [`CODING_CONVENTIONS.md`](CODING_CONVENTIONS.md).
- **Tests**: pytest on the backend, instrumented tests on Android.
  PRs adding business logic without tests are OK for very small cases
  (UI tweaks, copy), not for logic (recurrence, bonus, fair-share,
  sync).
- **No leftover `print()` or `Log.d`** in production code (linter lets
  them pass, common sense rejects them).
- **No emojis in code.** Emojis are OK in user-facing strings when
  intentional and consistent with existing UI.

## Commit convention

Free format, with a few rules:

- Prefix with the target version if it's part of a release
  (`v0.32.0: …`), otherwise with the type (`feat:`, `fix:`,
  `refactor:`, `docs:`, `chore:`).
- Title fits in ~70 chars. The body describes the why.
- Commits touching the DB schema must reference the corresponding
  Alembic migration.

## Alembic migrations

- One migration = one goal. No "while we're at it" migration doing 5
  unrelated things.
- Always think about `downgrade()` — even if we won't test it in prod,
  it's useful for local rollback.
- Personal data: never put a nominative seed in a migration (e.g.
  `INSERT INTO users (name) VALUES ('Alice')`). Real data lives in the
  DB, not in code.

## Personal data in the repo

This repo is meant to be public. Before each commit:

- No first name, email, IP, WiFi SSID, device identifier or personal
  domain in plain text in the code.
- For examples in docs or docstrings, use placeholders (Alice/Bob/X/Y
  or "member A").
- Real fixtures live in `backend/seeds/personal/` (gitignored).

## User data (GDPR)

If you touch the User model or auth/lifecycle endpoints:

- Any personal data must be deletable by the user (endpoint
  `DELETE /api/me`, soft-delete with purge ≥30 days).
- No log should contain PII (no email, no display name, no task
  content). UUID IDs are OK.

## Review process

1. Green CI (backend lint + Android + tests)
2. Maintainer reads, comments, asks for changes or approves
3. Squash-merge onto main with a clean message
4. Maintainer tags releases; no manual tag in PRs
