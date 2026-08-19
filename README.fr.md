# Torchons & Serviettes

[![Licence : AGPL v3][agpl]](LICENSE)
[![Plateforme : Android][android]](#architecture)
[![Backend : FastAPI][fastapi]](#architecture)

> 🇬🇧 [Read in English](README.md)

Une app de tâches ménagères collaboratives pour les foyers (couples,
colocations, familles). Pensée pour rendre le « qui fait quoi » moins
relou : on suggère ce qu'il faut faire en priorité, on compte les
points pour vérifier que la charge est partagée équitablement, et
on adapte les cadences à mesure que la vie change.

> **État** : pré-public, en route vers Play Store. Backend self-hosted
> par le dev solo et son foyer dogfood pendant la phase de stabilisation.

## Ce que ça fait

- **Tâches récurrentes** avec quatre modèles : interval (« tous les 3
  jours »), weekdays (« lundi/mercredi/vendredi »), monthly (« le 1er
  du mois »), uncertain (« quand y'a besoin »).
- **Modèle d'urgence déterministe** basé sur la due date — couleurs
  vert/orange/rouge et tri intra-tier de tolérance (Strict > Normal
  > Souple) sur la home.
- **Gamification** : points par complétion, bonus retard et malus
  avance proportionnels au cycle, badges, streaks, leaderboard,
  fair-share entre membres. **Optionnelle** : opt-out par user ou
  par foyer dans les Réglages.
- **Fair-share** : un ratio cible configurable (50/50, 60/40…) et
  un suivi de l'écart réel sur 30 jours glissants.
- **Mode offline** : Room cache local + change_log côté serveur,
  écrans utilisables sans réseau, sync en arrière-plan.
- **Onboarding sans compte** : QR pairing pour rejoindre un foyer,
  code de récupération pour restaurer l'accès sur un nouvel appareil.

## Architecture

- **Backend** : FastAPI (Python 3.14), PostgreSQL 16, SQLAlchemy 2
  async, Alembic pour les migrations.
- **Android** : Kotlin + Jetpack Compose + Room + Retrofit + Hilt.
  Min SDK 26, target SDK 35.
- **Sync offline** : `change_log` côté serveur + cursor par device
  côté client.

## Démarrer en local

Prérequis : Docker, Python 3.14, JDK 21+ (ou le JBR d'Android Studio),
Android SDK pour builder l'APK.

```bash
# Backend
docker compose up -d                            # Postgres sur :5435
cd backend
python -m venv .venv && .venv/Scripts/activate  # ou source .venv/bin/activate
pip install -r requirements.txt
PYTHONPATH=. alembic upgrade head
PYTHONPATH=. uvicorn app.main:app --host 0.0.0.0 --port 8002
```

Pour l'Android, ajoute dans `android/local.properties` (gitignored) :

```
ts.api.base.url=http://10.0.2.2:8002/api
```

(`10.0.2.2` = host depuis l'émulateur Android. Pour un device physique
sur le même LAN, mets l'IP de ta machine.)

Puis build :

```bash
cd android
JAVA_HOME=/path/to/jdk ./gradlew assembleDebug
```

Pas besoin de seed d'users — l'onboarding wizard de l'app crée tout
au premier lancement.

## Structure du repo

```
backend/
  app/
    api/              # routers FastAPI
    models/           # SQLAlchemy ORM
    services/         # logique métier (bonus_malus, sync, recurrence…)
    schemas/          # Pydantic
    content/          # changelog.md + roadmap.md user-facing
  alembic/versions/   # migrations
  seeds/              # docs + dossier personal/ (gitignored)
android/
  app/src/main/java/com/torchons/app/
    data/             # Retrofit, Room, repos
    ui/               # Compose screens + ViewModels
    worker/           # WorkManager (daily notif, sync)
docs/
  cache-conventions.md  # règles offline-first / sync / refresh (EN, FR : .fr.md)
  module-registry.fr.md # registre des modules + découverte progressive
```

## Statut

Le développement actuel se concentre sur la stabilisation pré-Play-Store.
Le changelog user-facing est dans
[`backend/app/content/changelog.md`](backend/app/content/changelog.md) ;
il est exposé dans l'app via `/api/changelog`.

## Contribuer

Voir [`CONTRIBUTING.fr.md`](CONTRIBUTING.fr.md) et
[`CODING_CONVENTIONS.fr.md`](CODING_CONVENTIONS.fr.md).

## Sécurité

Pour signaler une vulnérabilité, voir [`SECURITY.fr.md`](SECURITY.fr.md).

## Licence

AGPL-3.0 — voir [`LICENSE`](LICENSE). Si tu héberges une instance
modifiée accessible en réseau, tu dois rendre le code source de tes
modifications disponible aux utilisateurs de cette instance.

[agpl]: https://img.shields.io/badge/Licence-AGPL_v3-blue.svg
[android]: https://img.shields.io/badge/Plateforme-Android-3DDC84.svg?logo=android&logoColor=white
[fastapi]: https://img.shields.io/badge/Backend-FastAPI-009688.svg?logo=fastapi&logoColor=white
