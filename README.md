# Torchons & Serviettes

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android-3DDC84.svg?logo=android&logoColor=white)](#architecture)
[![Backend: FastAPI](https://img.shields.io/badge/Backend-FastAPI-009688.svg?logo=fastapi&logoColor=white)](#architecture)

> 🇫🇷 [Lire en français](README.fr.md)

A collaborative household chores app for homes — couples, flat-shares,
families. Designed to make the "who does what" question less of a
recurring fight: the app suggests what should be done first, counts
points to verify the load is fairly shared, and adapts the cadence as
life changes.

> **Status**: pre-public, on the way to Play Store. Backend self-hosted
> by the solo dev and his dogfood household during stabilisation.

## What the app does

- **Recurring tasks** with four models: interval ("every 3 days"),
  weekdays ("Mon/Wed/Fri"), monthly ("on the 1st"), uncertain ("when
  needed").
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

- **Backend**: FastAPI (Python 3.14), PostgreSQL 16, SQLAlchemy 2
  async, Alembic for migrations.
- **Android**: Kotlin + Jetpack Compose + Room + Retrofit + Hilt.
  Min SDK 26, target SDK 35.
- **Offline sync**: server-side `change_log` + per-device cursor
  client-side.

## Run locally

Prerequisites: Docker, Python 3.14, JDK 21+ (or the JBR bundled with
Android Studio), Android SDK to build the APK.

```bash
# Backend
docker compose up -d                            # Postgres on :5435
cd backend
python -m venv .venv && .venv/Scripts/activate  # or source .venv/bin/activate
pip install -r requirements.txt
PYTHONPATH=. alembic upgrade head
PYTHONPATH=. uvicorn app.main:app --host 0.0.0.0 --port 8002
```

For Android, add to `android/local.properties` (gitignored):

```
ts.api.base.url=http://10.0.2.2:8002/api
```

(`10.0.2.2` = host as seen from the Android emulator. For a physical
device on the same LAN, use your machine's IP.)

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
  seeds/              # docs + personal/ folder (gitignored)
android/
  app/src/main/java/com/torchons/app/
    data/             # Retrofit, Room, repos
    ui/               # Compose screens + ViewModels
    worker/           # WorkManager (daily notif, sync)
docs/
  cache-conventions.md  # offline-first / sync / refresh rules
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
