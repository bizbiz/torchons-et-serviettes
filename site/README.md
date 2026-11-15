# site/ — site statique public Torchons & Serviettes

Pages légales et vitrine, en français (racine) et en anglais (`en/`), servies par
**Cloudflare Workers** (Static Assets) d'après `wrangler.toml`, à la racine du dépôt.
Zéro build côté hébergeur, zéro dépendance, zéro requête externe (cohérent avec la
politique de confidentialité : pas de Google Fonts, de CDN ni d'analytics).

## Contenu

| Fichier | URL publique | Origine |
|---|---|---|
| `index.html` | `/` | à la main, sauf le bloc de cartes (généré) |
| `about.html` | `/about` | à la main |
| `faq.html` | `/faq` | à la main |
| `privacy.html` | `/privacy` | à la main |
| `contact.html` | `/contact` | à la main |
| `changelog.html` | `/changelog` | **généré**, ne pas éditer |
| `roadmap.html` | `/roadmap` | **généré**, ne pas éditer |
| `404.html` | page d'erreur (`not_found_handling = "404-page"`) | à la main |
| `styles.css` | feuille de style unique partagée | à la main |
| `en/*.html` | `/en/...`, mêmes pages en anglais | idem, page par page |

Les pages générées et le bloc `CARDS:START` / `CARDS:END` de `index.html` viennent de
`backend/app/content/` (`changelog.md`, `changelog.en.md`, `modules.yaml`, la feuille de
route). Pour les régénérer, depuis la racine du dépôt :

    backend/.venv/Scripts/python.exe scripts/gen_site_content.py

Le script est idempotent. Le site et l'application affichent ainsi le même contenu.

`html_handling = auto-trailing-slash` fait servir `privacy.html` à `/privacy` comme à
`/privacy.html`. Les liens internes gardent l'extension `.html` pour rester valides en
prévisualisation locale (`python -m http.server`).

## Déploiement (ops)

Workers Builds, connecté au dépôt Git, lance `npx wrangler deploy` à la racine : il lit
`wrangler.toml` et publie le dossier `./site`. Aucune commande de build à saisir.
Le domaine `torchonsetserviettes.app` est rattaché au Worker. Le sous-domaine de l'API
(`api.`) reste géré par le reverse proxy du backend et ne doit pas pointer sur ce site.

## Avant l'ouverture publique

- Lien Google Play (`index.html`) : après publication de la fiche.
- Lien du dépôt public (`about.html`, `contact.html`, `privacy.html` et leurs jumeaux
  `en/`) : ajouté le jour de la publication ; les pages le disent déjà.
- Lien Buy Me a Coffee (`contact.html`) : après création du compte.
- Routage de `contact@torchonsetserviettes.app` par Cloudflare Email Routing.
- La politique de confidentialité nomme déjà le responsable du traitement. Elle n'est
  pas un avis juridique : une relecture par un tiers compétent reste à faire (âge
  minimum, durées, mentions).
