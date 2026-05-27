# Conventions de code — Torchons & Serviettes

> 🇬🇧 [Read in English](CODING_CONVENTIONS.md)

Référence technique pour contributeurs. Couvre le style de code,
l'architecture, la surface API, le modèle de récurrence, le modèle
d'urgence, et les règles cache & sync. Pour les conventions projet
(process PR, format de commit), voir
[CONTRIBUTING.fr.md](CONTRIBUTING.fr.md).

## Conventions de langue dans le code

- **Nouveau code (écrit à partir de maintenant)** : commentaires,
  docstrings et identifiants en **anglais** — pour préparer l'i18n et
  faciliter les contributions externes.
- **Code français existant** : reste tel quel, pas de churn. Une
  migration bulk est cadrée comme chantier post-launch dédié.
- **Strings user-facing** (chaînes vues par l'utilisateur) : i18n-ready.
  Android via `res/values/strings.xml` (FR par défaut) +
  `res/values-en/strings.xml` (EN) + `stringResource(R.string.…)`.
  Plus de `Text("…")` littéraux user-visible. Backend
  `HTTPException(detail=…)` user-visible : utiliser
  `LocalizedHTTPException` avec clés client (compatible offline-first).

## Architecture

- **Backend** FastAPI (Python) : API REST + sync offline + auth JWT
- **Android** Kotlin natif : Jetpack Compose + Room + Retrofit
- **DB** PostgreSQL 16 : UUID partout, soft deletes, table `change_log`
  pour la sync

## Phases (déploiement features)

- **P1** — CRUD tâches + récurrence + attribution + catégories + sync offline
- **P2** — Gamification (urgence due_date, séries, classement)
- **P3** — Part équitable (ratio configurable + points par tâche)
- **P4** — Notifications push (Firebase) + rappels locaux

## Surface API (base `/api`)

| Groupe | Endpoints |
|--------|-----------|
| Auth | `POST /auth/login`, `GET /auth/me` |
| Tasks | `GET/POST/PUT/DELETE /tasks` |
| Categories | `GET/POST/PUT/DELETE /categories` |
| Completions | `POST /tasks/{id}/complete`, `GET /completions` |
| Sync | `POST /sync` |
| Gamification (P2) | `GET /dashboard`, `/streaks`, `/leaderboard` |
| Fair-share (P3) | `GET/PUT /fair-share/config`, `GET /fair-share/balance` |
| Notifications (P4) | `POST/DELETE /notifications/register` |

## Récurrence (forme JSONB)

```json
{"type": "interval",    "every_n_days": 3}
{"type": "weekdays",    "days": [1, 3, 5]}
{"type": "monthly",     "day_of_month": 1}
{"type": "custom",      "every_n_weeks": 2, "day": 2}
```

## Urgence (depuis v0.10.0 — refonte freshness)

Modèle déterministe basé sur `due_date` (plus de demi-vie).
`due_date = last_completed + intervalle` (ou `created_at` si jamais
faite). États :

- **Rouge** — `today > due_date`
- **Orange** — `due_date - window <= today <= due_date`, avec
  `window = floor(intervalle * 0.10)` (7j → 0, 30j → 3, 60j → 6)
- **Vert** — sinon

`tolerance` (STRICT / NORMAL / SOUPLE) ne change rien à l'état ni au
flag "en retard". Elle sert uniquement de tier de priorité pour le
sélecteur "la plus urgente" sur l'accueil : tier Strict > tier Normal >
tier Souple (cascade), puis fallback sur les Orange flat. Tri
intra-tier : ratio `retard / intervalle` desc.

## Cache / offline-first (OBLIGATOIRE)

Avant de toucher au cache Room, refresh, sync ou état dérivé, lire
[`docs/cache-conventions.md`](docs/cache-conventions.md) en entier.
TL;DR :

1. État dérivé recalculé à **chaque mutation** : écriture locale
   optimiste + enqueue → recalcul immédiat, **sans réseau**.
2. Offline = on fait confiance au front. Online = serveur autoritatif.
3. Refresh online = **REPLACE** du scope (`replaceAll` /
   `replaceForTask`), jamais upsert-only — sinon lignes fantômes et
   suppressions non propagées s'accumulent.

Patterns, anti-patterns et checklist complète :
[`docs/cache-conventions.md`](docs/cache-conventions.md).

## Ordre de sync

Un client émettant un NOUVEAU type d'op `/sync` exige que le handler
serveur correspondant soit **déployé AVANT** l'APK qui l'émet — sinon
le serveur rejette l'op et le client reste bloqué dans sa retry-queue.
