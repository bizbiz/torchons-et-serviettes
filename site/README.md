# site/ — site statique public Torchons & Serviettes

Pages légales + vitrine, déployées sur **Cloudflare Pages**. Zéro
build, zéro dépendance, zéro requête externe (cohérent avec la
politique de confidentialité : pas de Google Fonts/CDN/analytics).

## Contenu

| Fichier | URL publique |
|---|---|
| `index.html` | `/` |
| `about.html` | `/about` |
| `privacy.html` | `/privacy` |
| `contact.html` | `/contact` |
| `404.html` | page d'erreur (servie automatiquement par Cloudflare Pages) |
| `styles.css` | feuille de style unique partagée |

Cloudflare Pages sert les **clean URLs** automatiquement : `privacy.html`
est accessible à `/privacy` comme à `/privacy.html`. Les liens internes
utilisent l'extension `.html` pour rester valides aussi en prévisualisation
locale (`file://` ou `python -m http.server`).

## Déploiement (ops)

1. Cloudflare Dashboard → Pages → Create project → connecter le repo Git.
2. **Build command** : *(vide)* — site statique pur.
3. **Build output directory** : `site`
4. Domaine : rattacher `torchonsetserviettes.app` (et/ou `www`) au projet
   Pages. Le sous-domaine API (`api.torchonsetserviettes.app`) reste géré
   séparément par le reverse proxy backend (Caddy) — ne pas le faire
   pointer sur Pages.

## À compléter avant mise en ligne (placeholders `TODO ops` dans les pages)

- Lien Google Play (`index.html`) — après publication de la fiche (Phase F).
- Lien dépôt public GitHub (`about.html`, `contact.html`) — après bascule
  repo public (Phase E).
- Lien Buy Me a Coffee (`contact.html`) — après création du compte (Phase F).
- Routage `contact@torchonsetserviettes.app` via Cloudflare Email Routing.
- **Identité de l'éditeur / responsable de traitement** dans
  `privacy.html` (bloc `TODO`) — obligatoire légalement, à renseigner par
  l'éditeur. Faire relire la politique par un tiers compétent : ce n'est
  pas un avis juridique, c'est un gabarit aligné CNIL à valider.
