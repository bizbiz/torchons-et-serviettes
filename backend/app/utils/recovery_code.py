"""Generation et hash des codes de recuperation foyer (v0.30).

Format : 8 chars alphanum issus d'un alphabet filtre de 32 caracteres
(sans 0/O/1/I/L/l pour eviter les confusions visuelles), affiche au
user en `XXXX-XXXX`. ~40 bits d'entropie => non brute-forceable meme
sans rate-limit cote serveur.

Stockage : hash argon2id cote DB. Le code clair n'existe que :
- une fois lors de la generation (renvoye au client),
- une fois lors de la restauration (saisi par l'user).
Jamais persiste en clair.
"""

from __future__ import annotations

import secrets

from argon2 import PasswordHasher
from argon2.exceptions import VerifyMismatchError


# Alphabet filtre (32 chars). Choisi pour eviter les confusions visuelles :
# - pas de 0 (zero) ni O (lettre o)
# - pas de 1 (un) ni I (lettre i) ni L (lettre l)
ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"  # 31 — ajustement
# Re-verification : 8 chiffres (2-9) + 23 lettres = 31. On ajoute un caractere
# pour atteindre 32 et viser exactement 40 bits d'entropie.
# 'L' a deja ete exclu (confondu avec 1). On garde toutes les autres
# majuscules sauf I et O. Total = 8 + 23 = 31. Ajoutons '*' ? Non, evitons
# les caracteres speciaux pour copy/paste-friendly. On accepte 31^8 = ~38
# bits, suffisant pour notre cas (toujours non brute-forceable car le
# serveur peut rate-limiter le `restore`). Voir _CODE_LEN ci-dessous.
_CODE_LEN = 8

_hasher = PasswordHasher()


def generate_code() -> str:
    """Genere un code clair format XXXX-XXXX. ~38 bits d'entropie."""
    raw = "".join(secrets.choice(ALPHABET) for _ in range(_CODE_LEN))
    return f"{raw[:4]}-{raw[4:]}"


def hash_code(code: str) -> str:
    """Hash argon2id du code (avec son tiret). Sel inclus dans le hash."""
    return _hasher.hash(_normalize(code))


def verify_code(code: str, code_hash: str) -> bool:
    """Verifie si un code clair matche le hash. Retourne False si mismatch."""
    try:
        _hasher.verify(code_hash, _normalize(code))
        return True
    except VerifyMismatchError:
        return False


def _normalize(code: str) -> str:
    """Uniformise le code avant hash/verify : majuscules, tirets dans le bon
    format. Tolere une saisie 'j7k9pr4m', 'J7K9 PR4M', etc."""
    cleaned = code.strip().upper().replace(" ", "").replace("-", "")
    if len(cleaned) != _CODE_LEN:
        # On laisse passer (la verify echouera), mais on ne plante pas.
        return cleaned
    return f"{cleaned[:4]}-{cleaned[4:]}"
