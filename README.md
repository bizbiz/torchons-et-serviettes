# Torchons & Serviettes

[![License: AGPL v3][agpl]](LICENSE)
[![Platform: Android][android]](#architecture)
[![Backend: FastAPI][fastapi]](#architecture)

> 🇫🇷 [Lire en français](README.fr.md)

A collaborative household chores app for homes — couples, flat-shares,
families. Designed to make the "who does what" question less of a
recurring fight: the app suggests what should be done first, counts
points to verify the load is fairly shared, and adapts the cadence as
life changes.

> **Status**: pre-public, on the way to Play Store. Backend self-hosted
> by the solo dev and his dogfood household during stabilisation.

## What the app does

- **Recurring tasks** with five models: interval ("every 3 days"),
  monthly_variable (a different delay per month), uncertain ("when
  needed"), calendar ("every 15 May") and anniversary ("every 12
  months from the first time").
- **Deterministic urgency model** based on due-date — green/orange/red
  colours and intra-tier ordering (Strict > Normal > Souple) on the
  home screen.
- **Gamification**: points per completion, late bonus and early malus
  proportional to the cycle, badges, streaks, leaderboard, fair-share
  between members. **Optional**: opt-out per user or per household in
  Settings.
- **Fair-share**: a configurable target ratio (50/50, 60/40…) and
  tracking of the actual gap over a rolling 30-day window.
- **Offline mode**: local Room cache + server-side `change_log`,
  screens usable without network, background sync.
- **No-account onboarding**: QR pairing to join a household, recovery
  code to restore access on a new device.

## Architecture

- **Backend**: FastAPI (Python 3.12), PostgreSQL 16, SQLAlchemy 2
  async, Alembic for migrations.
- **Android**: Kotlin + Jetpack Compose + Room + Retrofit + Hilt.
  Min SDK 26, target SDK 35.
- **Offline sync**: server-side `change_log` + per-device cursor
  client-side.

## Run locally

Prerequisites: Docker, Python 3.12 (the version of the Docker image and of
`backend/requirements.lock`; `pyproject.toml` allows 3.11 and later), JDK 17
or newer (or the JBR bundled with Android Studio), Android SDK to build the APK.

```bash
# Configuration: .env at the repo root, read by docker compose and by the backend
cp .env.example .env
# Edit .env and replace every CHANGE_ME. POSTGRES_PASSWORD and the password inside
# DATABASE_URL / DATABASE_URL_SYNC must match. JWT_SECRET is required: the backend
# refuses to start if it is empty, still CHANGE_ME or shorter than 16 characters
# (python -c "import secrets; print(secrets.token_urlsafe(32))" makes one).

# Backend
docker compose up -d                            # Postgres on :5435
cd backend
python -m venv .venv && .venv/Scripts/activate  # or source .venv/bin/activate
pip install -r requirements.lock                # resolved versions, as in production
pip install "pytest>=8.0" "pytest-asyncio>=0.24"  # only to run the tests
PYTHONPATH=. alembic upgrade head
PYTHONPATH=. uvicorn app.main:app --host 0.0.0.0 --port 8002
```

The backend tests (`cd backend && python -m pytest`) write to the database that `.env`
points to: use a throwaway one, never a database you care about.

For Android, add to `android/local.properties` (gitignored):

```
ts.api.base.url=http://10.0.2.2:8002/api
```

(`10.0.2.2` = host as seen from the Android emulator. For a physical
device on the same LAN, use your machine's IP.)

The build applies the Google Services plugin, which needs a `google-services.json`
(git-ignored: it holds the keys of a real Firebase project). Start from the example
file, whose values are fake:

```bash
cp android/app/google-services.example.json android/app/google-services.json
```

The app builds and runs with it, but push notifications will not work: for those,
replace it with the file of your own Firebase project (applicationIds
`com.torchons.app` and `com.torchons.app.beta`).

Then build:

```bash
cd android
JAVA_HOME=/path/to/jdk ./gradlew assembleDebug
```

No user seeding needed — the in-app onboarding wizard creates
everything on first launch.

## Repo layout

```
backend/
  app/
    api/              # FastAPI routers
    models/           # SQLAlchemy ORM
    services/         # business logic (bonus_malus, sync, recurrence…)
    schemas/          # Pydantic
    content/          # user-facing changelog.md + roadmap.md
  alembic/versions/   # migrations
  tests/              # pytest suite
  seeds/              # docs + personal/ folder (gitignored)
android/
  app/src/main/java/com/torchons/app/
    data/             # Retrofit, Room, repos
    ui/               # Compose screens + ViewModels
    worker/           # WorkManager (daily notif, sync)
  app/src/test/       # JVM unit tests
docs/                 # each document has a .fr.md twin
  architecture.md       # overview, recurrence models
  cache-conventions.md  # offline-first / sync / refresh rules
  contexts.md           # contexts (replacing categories and rooms)
  module-registry.md    # module registry + progressive disclosure
  appareils.md          # devices: sessions, revocation
  version-gate.md       # taking an APK version out of circulation
  self-hosting.md       # which code is running where
scripts/              # deployment checks, site generation, catalog import
site/                 # static public website (legal pages, showcase)
```

## Status

Current development focuses on pre-Play-Store stabilisation.
The user-facing changelog lives in
[`backend/app/content/changelog.md`](backend/app/content/changelog.md);
it is exposed in-app via `/api/changelog`.

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md) and
[`CODING_CONVENTIONS.md`](CODING_CONVENTIONS.md).

## Security

To report a vulnerability, see [`SECURITY.md`](SECURITY.md).

## License

AGPL-3.0 — see [`LICENSE`](LICENSE). If you host a modified instance
accessible over a network, you must make the source code of your
modifications available to the users of that instance.

The Android app uses three non-free Google libraries (Play billing, Firebase notifications,
ML Kit QR scanning): an additional permission (AGPL section 7) allows redistributing the app
with them, see [`LICENSE-EXCEPTIONS.md`](LICENSE-EXCEPTIONS.md).

[agpl]: https://img.shields.io/badge/License-AGPL_v3-blue.svg
[android]: https://img.shields.io/badge/Platform-Android-3DDC84.svg?logo=android&logoColor=white
[fastapi]: https://img.shields.io/badge/Backend-FastAPI-009688.svg?logo=fastapi&logoColor=white
