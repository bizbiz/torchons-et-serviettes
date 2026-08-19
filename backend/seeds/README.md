# Seeds

Le bootstrap d'un nouveau foyer se fait via l'onboarding wizard de l'app
Android (Create Foyer → Wizard 4 steps → contextes et taches importes via
`/api/catalog/*`). Aucun seed script n'est requis pour utiliser l'app.

## `personal/` (gitignored)

Dossier reserve aux fixtures liees a un foyer reel : bootstrap dogfood
(creation hardcoded de 2 users specifiques), imports d'historique Home
Assistant, dumps de DB pour debug, etc. Tout ce qui est `personal/` est
exclu du repo via `.gitignore` racine et ne doit jamais etre publie.

Si tu reconstruis ton env de zero et veux re-bootstrapper avec tes users
existants, garde ton script personnel dans ce dossier (ex.
`seed_bootstrap_dogfood.py`) — il n'apparaitra pas dans `git status`.

## Pour un dev qui clone le repo from-scratch

1. `docker compose up -d` (Postgres)
2. `alembic upgrade head` (schema)
3. Lance le backend : `uvicorn app.main:app --host 0.0.0.0 --port 8002`
4. Build et lance l'app Android, choisis "Creer un foyer" sur l'ecran
   d'accueil — le wizard prend la main.

Pas besoin de pre-seed d'users : c'est le wizard qui les cree.
