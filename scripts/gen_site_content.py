#!/usr/bin/env python3
"""Génère site/changelog.html + site/roadmap.html depuis la SOURCE UNIQUE.

Source de vérité = `backend/app/content/changelog.md` et `roadmap.md`,
exactement les mêmes fichiers que l'app sert via `GET /api/changelog` et
`GET /api/roadmap` (cf `backend/app/api/content.py`). Le site et l'app
affichent donc le MÊME contenu — aucun décalage possible entre versions.

À relancer chaque fois que ces .md changent (typiquement à chaque release,
quand on met à jour le changelog). Idempotent. Stdlib uniquement, aucune
dépendance — cohérent avec le principe « site statique, zéro build lourd,
zéro requête externe au runtime » (le markdown est figé en HTML ici, pas
fetché côté client).

Usage : python scripts/gen_site_content.py
"""

from __future__ import annotations

import html
import re
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTENT = ROOT / "backend" / "app" / "content"
SITE = ROOT / "site"

PAGE = """<!DOCTYPE html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title} — Torchons &amp; Serviettes</title>
<meta name="description" content="{desc}">
<meta name="theme-color" content="#0f766e">
<meta property="og:type" content="website">
<meta property="og:site_name" content="Torchons & Serviettes">
<meta property="og:title" content="{title} — Torchons & Serviettes">
<meta property="og:description" content="{desc}">
<meta property="og:url" content="https://torchonsetserviettes.app/{slug}">
<meta name="twitter:card" content="summary">
<link rel="stylesheet" href="styles.css">
</head>
<body>
<header class="site">
  <div class="wrap">
    <a class="brand" href="index.html"><span class="dot"></span>Torchons &amp; Serviettes</a>
    <nav class="site">
      <a href="about.html">À propos</a>
      <a href="faq.html">FAQ</a>
      <a href="privacy.html">Confidentialité</a>
      <a href="contact.html">Contact</a>
      <span class="lang-switch" title="English translation pending"><span style="opacity:.4">EN</span> · <strong>FR</strong></span>
    </nav>
  </div>
</header>

<main>
  <div class="wrap">
    <h1>{title}</h1>
    <p class="lead">{lead}</p>
    <p class="meta">Généré automatiquement depuis la même source que
      l'application — aucun décalage entre les versions.
      <small>(mise à jour : {today})</small></p>
{body}
  </div>
</main>

<footer class="site">
  <div class="wrap">
    <span>© <span id="y">2026</span> Torchons &amp; Serviettes</span>
    <span>
      <a href="about.html">À propos</a> ·
      <a href="faq.html">FAQ</a> ·
      <a href="changelog.html">Nouveautés</a> ·
      <a href="roadmap.html">Feuille de route</a> ·
      <a href="privacy.html">Confidentialité</a> ·
      <a href="contact.html">Contact</a>
    </span>
  </div>
</footer>
<script>document.getElementById('y').textContent=new Date().getFullYear();</script>
</body>
</html>
"""

_BOLD = re.compile(r"\*\*(.+?)\*\*")
_ITALIC = re.compile(r"(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)")
_CODE = re.compile(r"`([^`]+?)`")


def _inline(text: str) -> str:
    """Échappe le HTML puis applique gras / italique / code inline."""
    out = html.escape(text)
    out = _BOLD.sub(r"<strong>\1</strong>", out)
    out = _CODE.sub(r"<code>\1</code>", out)
    out = _ITALIC.sub(r"<em>\1</em>", out)
    return out


def md_to_html(md: str) -> str:
    """Conversion markdown -> HTML pour le sous-ensemble utilisé par les .md
    (titres ##/###, listes -, gras, italique, code, paragraphes). Les lignes
    de continuation indentées sont rattachées à la puce précédente."""
    lines = md.splitlines()
    parts: list[str] = []
    in_list = False
    para: list[str] = []

    def flush_para() -> None:
        if para:
            parts.append("<p>" + _inline(" ".join(para).strip()) + "</p>")
            para.clear()

    def close_list() -> None:
        nonlocal in_list
        if in_list:
            parts.append("</ul>")
            in_list = False

    for raw in lines:
        line = raw.rstrip()
        stripped = line.strip()

        if not stripped:
            flush_para()
            close_list()
            continue

        if stripped.startswith("# "):
            # h1 du fichier ignoré : le titre de page le remplace.
            flush_para()
            close_list()
            continue
        if stripped.startswith("### "):
            flush_para()
            close_list()
            parts.append("<h3>" + _inline(stripped[4:]) + "</h3>")
            continue
        if stripped.startswith("## "):
            flush_para()
            close_list()
            parts.append("<h2>" + _inline(stripped[3:]) + "</h2>")
            continue
        if stripped.startswith("- "):
            flush_para()
            if not in_list:
                parts.append("<ul>")
                in_list = True
            parts.append("<li>" + _inline(stripped[2:]) + "</li>")
            continue
        # Ligne de continuation : rattachée à la puce ou au paragraphe courant.
        if in_list and raw.startswith(" ") and parts and parts[-1].startswith("<li>"):
            parts[-1] = parts[-1][:-5] + " " + _inline(stripped) + "</li>"
            continue
        para.append(stripped)

    flush_para()
    close_list()
    return "\n".join(parts)


def build(src_name: str, slug: str, title: str, desc: str, lead: str) -> Path:
    md = (CONTENT / src_name).read_text(encoding="utf-8")
    out = PAGE.format(
        title=title,
        desc=desc,
        slug=slug,
        lead=lead,
        today=date.today().isoformat(),
        body=md_to_html(md),
    )
    dest = SITE / f"{slug}.html"
    dest.write_text(out, encoding="utf-8", newline="\n")
    return dest


def main() -> None:
    a = build(
        "changelog.md", "changelog", "Nouveautés",
        "Les dernières évolutions de Torchons & Serviettes, version par version.",
        "Les dernières évolutions de l'application, version par version. "
        "Même contenu que l'écran « Nouveautés » de l'app.",
    )
    b = build(
        "roadmap.md", "roadmap", "Feuille de route",
        "Les idées et fonctionnalités prévues pour Torchons & Serviettes.",
        "Les idées et chantiers prévus pour la suite. Susceptible d'évoluer "
        "selon vos retours.",
    )
    print(f"généré : {a.relative_to(ROOT)}")
    print(f"généré : {b.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
