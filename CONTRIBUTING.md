# Contribuer à Torchons & Serviettes

Merci de t'intéresser au projet. C'est un projet solo à l'origine, en
phase de préparation publique — les contributions externes sont
bienvenues mais le rythme de revue dépend de la dispo du mainteneur.

## Avant d'ouvrir une PR

- **Discute d'abord** pour les changements substantiels (nouvelle
  feature, refonte d'écran, changement de modèle) : ouvre une issue
  pour décrire ce que tu veux faire, on s'aligne avant que tu codes.
  Les petits fixes / typos peuvent partir direct en PR sans discussion
  préalable.
- **Une PR = un sujet.** Évite de mixer un fix de bug + un refactor +
  une nouvelle feature dans la même PR.
- **Cible la branche `main`.** Pas de branche `develop`/`staging` —
  tout va sur `main` après revue.

## Style code

- **Backend** : type hints partout, async SQLAlchemy 2 (pas de session
  bloquante). Commentaires et docstrings en français.
- **Android** : Kotlin idiomatique, Compose pour toute nouvelle UI
  (pas de View XML sauf cas spécifique). Une fonction `@Composable`
  par fichier quand elle dépasse 100 lignes. Commentaires et
  docstrings en français.
- **Tests** : pytest côté backend, instrumented tests côté Android.
  Les PR qui ajoutent du métier sans test sont OK pour les très petits
  cas (UI tweaks, copy), pas pour la logique (recurrence, bonus,
  fair-share, sync).
- **Pas de `print()` ni `Log.d` oubliés** en code de prod (le linter
  les laisse passer, le sens commun les rejette).
- **Pas d'emojis dans le code.** Les emojis sont OK dans les strings
  affichées à l'utilisateur quand c'est intentionnel et cohérent
  avec l'existant.

## Convention de commit

Format libre, mais quelques règles :

- Préfixer par la version cible si c'est dans le cadre d'une release
  (`v0.32.0: …`), sinon par le type (`feat:`, `fix:`, `refactor:`,
  `docs:`, `chore:`).
- Le titre tient sur ~70 chars. Le body décrit le pourquoi.
- Les commits qui touchent au schéma DB doivent référencer la
  migration Alembic correspondante.

## Migrations Alembic

- Une migration = un objectif. Pas de migration "et pendant qu'on y
  est" qui fait 5 choses non liées.
- Toujours penser au `downgrade()` — même si on ne le testera pas
  en prod, c'est utile pour rollback local.
- Données personnelles : ne mets jamais de seed nominatif dans une
  migration (ex. `INSERT INTO users (name) VALUES ('Alice')`). Les
  données réelles vivent dans la DB, pas dans le code.

## Données personnelles dans le repo

Ce repo est destiné à être public. Avant chaque commit :

- Pas de prénom, email, IP, SSID WiFi, identifiant de device ou
  domaine personnel en clair dans le code.
- Pour les exemples dans la doc ou les docstrings, utilise des
  placeholders (Alice/Bob/X/Y ou « le membre A »).
- Les fixtures réelles vont dans `backend/seeds/personal/`
  (gitignored).

## Données utilisateur (RGPD)

Si tu touches au modèle User ou aux endpoints d'auth/lifecycle :

- Toute donnée perso doit pouvoir être supprimée par l'utilisateur
  (endpoint `DELETE /api/me`, soft-delete avec purge ≥30j).
- Aucun log ne doit contenir de PII (pas d'email, pas de
  display_name, pas de contenu de tâche). Les IDs UUID sont OK.

## Process de revue

1. CI verte (lint backend + Android + tests)
2. Mainteneur lit, commente, demande des changements ou approuve
3. Squash-merge sur main avec un message propre
4. Le mainteneur tag les releases ; pas de tag manuel dans les PR
