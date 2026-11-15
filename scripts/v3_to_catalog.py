"""Convertit le document de relecture v3 en catalog.yaml (contextes).

Le markdown est la source EDITORIALE — c'est là que se décident les tâches et
leurs libellés. Ce script en dérive la forme machine, pour qu'il n'y ait jamais
deux listes à tenir à jour. Toute divergence future se corrige dans le .md puis
se régénère ici.

English labels are NOT in the markdown (it is French only): they live in
catalog.yaml itself. Regenerating keeps every existing `en` label, matched by
`key`, and only seeds a NEW entry with its French label — see
keep_translations(). Without that, a regeneration silently reverted the whole
catalogue to French. Usage: `python scripts/v3_to_catalog.py DOC [--out PATH]`, DOC being the v3 review document.
"""
import argparse
import re
import sys
import unicodedata
from pathlib import Path

import yaml

CATALOG = Path(__file__).resolve().parent.parent / "backend" / "app" / "content" / "catalog.yaml"

MOIS = {
    "janvier": 1, "février": 2, "fevrier": 2, "mars": 3, "avril": 4, "mai": 5,
    "juin": 6, "juillet": 7, "août": 8, "aout": 8, "septembre": 9,
    "octobre": 10, "novembre": 11, "décembre": 12, "decembre": 12,
}
JOURS_PAR_MOIS = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]


def slug(txt: str) -> str:
    t = unicodedata.normalize("NFKD", txt).encode("ascii", "ignore").decode()
    t = re.sub(r"[^a-zA-Z0-9]+", "_", t).strip("_").lower()
    return re.sub(r"_+", "_", t)


def parse_fenetre(rythme: str) -> tuple[str, list[int] | None]:
    """Extrait « (avril–oct) » et rend le rythme nettoyé + les mois actifs."""
    m = re.search(r"\(([^)]*)\)", rythme)
    if not m:
        return rythme, None
    dedans = m.group(1)
    bornes = re.findall(r"[a-zéèûôîà]+", dedans.lower())
    idx = []
    for b in bornes:
        for nom, num in MOIS.items():
            if nom.startswith(b[:3]):
                idx.append(num)
                break
    reste = (rythme[: m.start()] + rythme[m.end():]).strip()
    if len(idx) == 2:
        a, b = idx
        actifs = list(range(a, b + 1)) if a <= b else list(range(a, 13)) + list(range(1, b + 1))
        return reste, actifs
    return reste, None


def parse_rythme(rythme: str, type_: str) -> dict | None:
    r = rythme.strip().lower()
    corps, fenetre = parse_fenetre(r)

    if type_ == "cal.":
        if "1 du mois" in corps:
            return {"type": "calendar", "months": list(range(1, 13)), "day": 1}
        if "anniversaire" in corps:
            return {"type": "interval", "every_n_days": 365}
        if "2 ans" in corps or "échéance" in corps or "echeance" in corps:
            return {"type": "interval", "every_n_days": 730}
        # « mai–août » = UNE occurrence tolérée jusqu'à la fin de la plage.
        # « avril & octobre » = DEUX occurrences. Le séparateur porte le sens.
        if "–" in corps or "-" in corps.replace("–", "-"):
            noms = re.findall(r"[a-zéèûôîà]+", corps)
            nums = [MOIS[n] for n in noms if n in MOIS]
            if len(nums) == 2:
                debut, fin = nums
                span = (fin - debut) % 12
                grace = sum(JOURS_PAR_MOIS[(debut - 1 + k) % 12] for k in range(span + 1))
                return {"type": "calendar", "months": [debut], "day": 1,
                        "grace_days": grace}
        noms = re.findall(r"[a-zéèûôîà]+", corps)
        nums = sorted({MOIS[n] for n in noms if n in MOIS})
        if nums:
            return {"type": "calendar", "months": nums, "day": 1}
        return None

    if type_ == "libre":
        return {"type": "uncertain"}

    # interv. — avec fenêtre de saison éventuelle : 365 hors saison, ce qui
    # rend la tâche dormante sans la supprimer (0 est refusé par le modèle).
    m = re.search(r"tous les (\d+)", corps)
    n = int(m.group(1)) if m else (1 if "tous les jours" in corps else None)
    if n is None:
        return None
    if fenetre:
        return {"type": "monthly_variable",
                "days_per_month": {str(mo): (n if mo in fenetre else 365) for mo in range(1, 13)}}
    return {"type": "interval", "every_n_days": n}


#: Context fields decided AFTER the markdown, applied on top of it. Without this
#: a regeneration silently undid them (measured 2026-10-01 on `courses`).
CONTEXT_OVERRIDES = {
    # Selectionnable et pre-coche, contrairement a menage / linge / entretien :
    # `courses` porte des taches PROPRES (faire les courses, verifier les
    # stocks), donc ce n'est pas un simple classement mais une activite. Le
    # laisser implicite rendait ces taches invisibles a vie — aucun contexte
    # selectionnable ne pouvait les faire apparaitre.
    "courses": {"default_selected": True, "selectable": True},
}


def apply_overrides(contextes: list[dict]) -> None:
    for c in contextes:
        c.update(CONTEXT_OVERRIDES.get(c["key"], {}))


def keep_translations(contextes: list[dict], taches: list[dict], existing: dict | None) -> None:
    """Carry the `en` labels of `existing` (the current catalog) over, by key.

    In place. A key absent from `existing`, or whose `en` is empty, keeps the
    French label the parser seeded it with. Entries removed from the markdown
    simply disappear with their translation: nothing to carry.
    """
    if not existing:
        return
    for items, field, section in ((contextes, "name", "contexts"), (taches, "title", "tasks")):
        known = {
            e["key"]: (e.get(field) or {}).get("en")
            for e in existing.get(section) or []
            if isinstance(e, dict) and "key" in e
        }
        for item in items:
            en = known.get(item["key"])
            if en:
                item[field]["en"] = en


def header_of(text: str) -> str:
    """Leading comment block of catalog.yaml, kept verbatim on regeneration."""
    out = []
    for line in text.splitlines(keepends=True):
        if line.startswith("#") or not line.strip():
            out.append(line)
        else:
            break
    return "".join(out)


def parse_doc(lignes: list[str]) -> tuple[list[dict], list[dict]]:
    contextes, taches = [], []
    ctx_courant = None
    vus = set()

    for i, ligne in enumerate(lignes):
        entete = re.match(r"^### (☑ )?(\S+ )?(.+)$", ligne)
        if entete:
            nom = entete.group(3).strip()
            key = slug(nom)
            ctx_courant = key
            contextes.append({
                "key": key,
                "name": {"fr": nom, "en": nom},
                "emoji": (entete.group(2) or "").strip() or None,
                "default_selected": bool(entete.group(1)),
                "selectable": True,
                "requires_presence": False,
                "accrues_when_away": False,
            })
            continue
        # Seule la PREMIERE ligne en italique porte les drapeaux. Les sections
        # sans tache propre en ont une seconde (« Aucune tache propre… ») qui
        # ecrasait la premiere et remettait tous les drapeaux a faux.
        if (ctx_courant and ligne.startswith("*") and ligne.endswith("*")
                and "|" not in ligne and not contextes[-1].get("_flags_lus")):
            drapeaux = ligne.strip("*").lower()
            c = contextes[-1]
            c["_flags_lus"] = True
            c["requires_presence"] = "présence" in drapeaux
            c["accrues_when_away"] = "accumule" in drapeaux
            c["selectable"] = "implicite" not in drapeaux
            continue
        if ctx_courant and ligne.startswith("|") and not ligne.startswith("|---") and "Tâche" not in ligne:
            cells = [c.strip() for c in ligne.split("|")[1:-1]]
            if len(cells) < 6:
                continue
            marque, titre, type_, rythme, pts, aussi = cells[:6]
            key = slug(titre)[:48]
            if key in vus:
                continue
            vus.add(key)
            autres = [slug(a) for a in aussi.split(",") if a.strip() and a.strip() != "—"]
            rule = parse_rythme(rythme, type_)
            t = {
                "key": key,
                "title": {"fr": titre, "en": titre},
                "contexts": [ctx_courant] + [a for a in autres if a != ctx_courant],
                "default_weight": int(pts) if pts.isdigit() else 1,
                "default_tolerance": "NORMAL",
            }
            if rule:
                t["default_recurrence"] = rule
            if "★" in marque:
                t["default_selected"] = True
            taches.append(t)
    return contextes, taches


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("doc", type=Path, help="the v3 review document (markdown) to convert")
    ap.add_argument("--out", type=Path, default=CATALOG,
                    help="where to write (default: the repo catalog.yaml)")
    args = ap.parse_args()

    contextes, taches = parse_doc(args.doc.read_text(encoding="utf-8").splitlines())
    apply_overrides(contextes)

    # --- Contrôles ---
    cles = {c["key"] for c in contextes}
    inconnus = sorted({c for t in taches for c in t["contexts"] if c not in cles})
    sans_regle = [t["key"] for t in taches if "default_recurrence" not in t]

    print(f"contextes : {len(contextes)}  (dont {sum(1 for c in contextes if not c['selectable'])} implicites)")
    print(f"taches    : {len(taches)}")
    print(f"etoilees  : {sum(1 for t in taches if t.get('default_selected'))}")
    print(f"contextes inconnus references : {inconnus or 'aucun'}")
    print(f"taches sans regle de recurrence : {len(sans_regle)} {sans_regle[:5]}")
    from collections import Counter
    print("types :", Counter(t.get("default_recurrence", {}).get("type") for t in taches))

    for c in contextes:
        c.pop("_flags_lus", None)

    current = CATALOG.read_text(encoding="utf-8") if CATALOG.exists() else ""
    keep_translations(contextes, taches, yaml.safe_load(current) if current else None)
    same = sum(1 for t in taches if t["title"]["en"] == t["title"]["fr"])
    print(f"titres en identiques au fr : {same} / {len(taches)}")

    args.out.write_text(
        header_of(current) + yaml.safe_dump({"contexts": contextes, "tasks": taches},
                                            allow_unicode=True, sort_keys=False, width=110),
        encoding="utf-8",
    )
    print(f"ecrit : {args.out}")


if __name__ == "__main__":
    main()
