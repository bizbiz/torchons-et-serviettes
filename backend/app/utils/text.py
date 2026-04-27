"""Utilitaires de formatage texte (FR)."""

from __future__ import annotations


def join_with_amp(names: list[str]) -> str:
    """Joint une liste de noms en francais : virgules + esperluette finale.

    Exemples :
        []                    -> ""
        ["A"]                 -> "A"
        ["A", "B"]            -> "A & B"
        ["A", "B", "C"]       -> "A, B & C"
        ["A", "B", "C", "D"]  -> "A, B, C & D"
    """
    if not names:
        return ""
    if len(names) == 1:
        return names[0]
    return ", ".join(names[:-1]) + " & " + names[-1]
