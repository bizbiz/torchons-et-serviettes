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

- **Tâches récurrentes** avec cinq modèles : interval (« tous les 3
  jours »), monthly_variable (un délai différent selon le mois),
  uncertain (« quand y'a besoin »), calendar (« tous les 15 mai ») et
  anniversary (« tous les 12 mois depuis la première fois »).
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

- **Backend** : FastAPI (Python 3.12), PostgreSQL 16, SQLAlchemy 2
  async, Alembic pour les migrations.
- **Android** : Kotlin + Jetpack Compose + Room + Retrofit + Hilt.
  Min SDK 26, target SDK 35.
- **Sync offline** : `change_log` côté serveur + cursor par device
  côté client.

## Démarrer en local

Prérequis : Docker, Python 3.12 (la version de l'image Docker et de
`backend/requirements.lock` ; `pyproject.toml` accepte 3.11 et plus), JDK 17
ou plus (ou le JBR d'Android Studio), Android SDK pour builder l'APK.

```bash
# Configuration : .env à la racine, lu par docker compose et par le backend
cp .env.example .env
# Édite .env et remplace chaque CHANGE_ME. POSTGRES_PASSWORD et le mot de passe dans
# DATABASE_URL / DATABASE_URL_SYNC doivent être identiques. JWT_SECRET est obligatoire :
# le backend refuse de démarrer s'il est vide, resté à CHANGE_ME ou plus court que 16
# caractères (python -c "import secrets; print(secrets.token_urlsafe(32))" en génère un).

# Backend
docker compose up -d                            # Postgres sur :5435
cd backend
python -m venv .venv && .venv/Scripts/activate  # ou source .venv/bin/activate
pip install -r requirements.lock                # versions résolues, comme en production
pip install "pytest>=8.0" "pytest-asyncio>=0.24"  # seulement pour lancer les tests
PYTHONPATH=. alembic upgrade head
PYTHONPATH=. uvicorn app.main:app --host 0.0.0.0 --port 8002
```

Les tests backend (`cd backend && python -m pytest`) écrivent dans la base que `.env`
désigne : prends-en une jetable, jamais une base qui compte.

Pour l'Android, ajoute dans `android/local.properties` (gitignored) :

```
ts.api.base.url=http://10.0.2.2:8002/api
```

(`10.0.2.2` = host depuis l'émulateur Android. Pour un device physique
sur le même LAN, mets l'IP de ta machine.)

Le build applique le plugin Google Services, qui exige un `google-services.json`
(gitignored : il porte les clés d'un vrai projet Firebase). Pars du fichier d'exemple,
dont les valeurs sont fausses :

```bash
cp android/app/google-services.example.json android/app/google-services.json
```

L'app compile et tourne avec lui, mais les notifications push ne fonctionneront pas :
pour elles, remplace-le par le fichier de ton propre projet Firebase (applicationId
`com.torchons.app` et `com.torchons.app.beta`).

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
  tests/              # suite pytest
  seeds/              # docs + dossier personal/ (gitignored)
android/
  app/src/main/java/com/torchons/app/
    data/             # Retrofit, Room, repos
    ui/               # Compose screens + ViewModels
    worker/           # WorkManager (daily notif, sync)
  app/src/test/       # tests unitaires JVM
docs/                 # chaque document a son jumeau .md (EN)
  architecture.fr.md      # vue d'ensemble, modèles de récurrence
  cache-conventions.fr.md # règles offline-first / sync / refresh
  contexts.fr.md          # contextes (remplacent catégories et pièces)
  module-registry.fr.md   # registre des modules + découverte progressive
  appareils.fr.md         # appareils : sessions, révocation
  version-gate.fr.md      # retirer une version d'APK de la circulation
  self-hosting.fr.md      # quel code tourne où
scripts/              # contrôles de déploiement, génération du site, import du catalogue
site/                 # site statique public (pages légales, vitrine)
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

L'app Android utilise trois bibliothèques non libres de Google (paiement Play, notifications
Firebase, lecture de QR code ML Kit) : une permission additionnelle (AGPL § 7) autorise à
redistribuer l'app avec elles, voir [`LICENSE-EXCEPTIONS.md`](LICENSE-EXCEPTIONS.md).

[agpl]: https://img.shields.io/badge/Licence-AGPL_v3-blue.svg
[android]: https://img.shields.io/badge/Plateforme-Android-3DDC84.svg?logo=android&logoColor=white
[fastapi]: https://img.shields.io/badge/Backend-FastAPI-009688.svg?logo=fastapi&logoColor=white
