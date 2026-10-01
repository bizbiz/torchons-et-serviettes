"""English labels of the template catalogue (catalog.yaml).

Two invariants, and the paths that can break them:

1. Every `en` label is a translation, not a copy of the French one.
   Broken by: (a) a hand edit of catalog.yaml; (b) a regeneration by
   scripts/v3_to_catalog.py, whose parser seeds `en` with the French label;
   (c) a new entry added to the markdown source. All three land in the YAML
   file, which test_every_english_label_is_translated reads.
2. A regeneration keeps the existing translations. Broken by: the merge in
   v3_to_catalog.keep_translations (covered here on synthetic input).
   Not covered: the full regeneration end to end, whose source markdown lives
   outside the repo (Desktop); measured by hand on 2026-10-01, the regenerated
   file only differed by a YAML comment moved into the script.
"""
from __future__ import annotations

import importlib.util
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "backend" / "app" / "content" / "catalog.yaml"

#: Labels that are the same word in both languages, checked to still be so.
SAME_IN_BOTH = {"garage", "compost"}


def _entries():
    data = yaml.safe_load(CATALOG.read_text(encoding="utf-8"))
    for c in data["contexts"]:
        yield c["key"], c["name"]
    for t in data["tasks"]:
        yield t["key"], t["title"]


def test_the_catalogue_is_read():
    keys = [k for k, _ in _entries()]
    assert len(keys) >= 200, f"only {len(keys)} entries read: wrong file or shape"


def test_every_english_label_is_translated():
    untranslated = sorted(
        k for k, labels in _entries()
        if labels.get("en") == labels.get("fr") and k not in SAME_IN_BOTH
    )
    missing = sorted(k for k, labels in _entries() if not labels.get("en"))
    assert not missing, f"no English label: {missing}"
    assert not untranslated, (
        f"{len(untranslated)} English label(s) still French: {untranslated[:10]}"
    )


def test_the_same_in_both_list_is_not_stale():
    labels = dict(_entries())
    stale = sorted(k for k in SAME_IN_BOTH if k not in labels or labels[k]["en"] != labels[k]["fr"])
    assert not stale, f"no longer identical, remove from SAME_IN_BOTH: {stale}"


def _generator():
    spec = importlib.util.spec_from_file_location("v3_to_catalog", ROOT / "scripts" / "v3_to_catalog.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_regeneration_keeps_existing_translations():
    gen = _generator()
    contextes = [{"key": "cuisine", "name": {"fr": "Cuisine", "en": "Cuisine"}},
                 {"key": "neuf", "name": {"fr": "Neuf", "en": "Neuf"}}]
    taches = [{"key": "faire_la_vaisselle", "title": {"fr": "Faire la vaisselle", "en": "Faire la vaisselle"}},
              {"key": "vide", "title": {"fr": "Vide", "en": "Vide"}}]
    existing = {
        "contexts": [{"key": "cuisine", "name": {"fr": "Cuisine", "en": "Kitchen"}},
                     {"key": "retire", "name": {"fr": "Retiré", "en": "Removed"}}],
        "tasks": [{"key": "faire_la_vaisselle", "title": {"fr": "Faire la vaisselle", "en": "Do the dishes"}},
                  {"key": "vide", "title": {"fr": "Vide", "en": ""}}],
    }
    gen.keep_translations(contextes, taches, existing)
    assert contextes[0]["name"]["en"] == "Kitchen"
    assert contextes[1]["name"]["en"] == "Neuf", "a new entry is seeded with French"
    assert taches[0]["title"]["en"] == "Do the dishes"
    assert taches[1]["title"]["en"] == "Vide", "an empty translation must not blank the label"
    assert [c["key"] for c in contextes] == ["cuisine", "neuf"], "removed keys must not come back"


def test_regeneration_keeps_the_comment_header():
    gen = _generator()
    text = "# one\n#\n# two\n\ncontexts:\n- key: a\n"
    assert gen.header_of(text) == "# one\n#\n# two\n\n"
