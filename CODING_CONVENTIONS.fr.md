# Conventions de code — Torchons & Serviettes

> 🇬🇧 [Read in English](CODING_CONVENTIONS.md)

Ce document porte ce qui lui est **propre** : les conventions de langue dans le
code, et le découpage en phases. Tout le reste — architecture, surface API,
récurrence, urgence, cache et ordre de sync — vit **ailleurs**, et n'est pas
recopié ici.

Ce n'est pas une préférence de rangement. Ces sections existaient en double,
reformulées, et elles avaient divergé sans que rien ne le signale : la section
Récurrence décrivait encore quatre types dont **trois étaient morts**, sur un
document que `backend/app/models/task.py` désignait comme la spécification des
formats. Un fait recopié ne reste pas synchronisé.

Pour le process (PR, format de commit) : [CONTRIBUTING.fr.md](CONTRIBUTING.fr.md).

## Où trouver quoi

| Sujet | Source unique |
|-------|---------------|
| Architecture (backend / Android / DB) | [`CLAUDE.md`](CLAUDE.md) § Architecture |
| Surface API | [`CLAUDE.md`](CLAUDE.md) § API |
| Formes de récurrence (4 types vivants) | [`docs/contexts.fr.md`](docs/contexts.fr.md) § Récurrence calendaire, et [`CLAUDE.md`](CLAUDE.md) § Recurrence |
| Modèle d'urgence (rouge / orange / vert) | [`CLAUDE.md`](CLAUDE.md) § Urgence |
| Contextes (remplacent catégories et pièces) | [`docs/contexts.fr.md`](docs/contexts.fr.md) |
| Cache et offline-first | [`docs/cache-conventions.fr.md`](docs/cache-conventions.fr.md) |
| Ordre de sync (serveur avant APK) | [`CLAUDE.md`](CLAUDE.md) § Sync ordering |
| Modules et découverte progressive | [`docs/module-registry.fr.md`](docs/module-registry.fr.md) |

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

## Phases (déploiement features)

- **P1** — CRUD tâches + récurrence + attribution + contextes + sync offline
- **P2** — Gamification (urgence due_date, séries, classement)
- **P3** — Part équitable (ratio configurable + points par tâche)
- **P4** — Notifications push (Firebase) + rappels locaux
