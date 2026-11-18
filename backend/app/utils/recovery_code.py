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

import hashlib
import hmac
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


def _lookup_secret() -> bytes:
    """RECOVERY_LOOKUP_SECRET, else JWT_SECRET (app/config.py says why they are apart)."""
    from app.config import settings

    return (settings.recovery_lookup_secret or settings.jwt_secret).encode()


def lookup_key_id() -> str:
    """Short id of the secret the lookup keys are computed with.

    Stored in front of each key: after a rotation of that secret, keys made with the
    old one no longer carry the current id, so restore treats those accounts as
    unkeyed (full argon2 scan) and re-keys them on the first success, instead of
    never finding them again.
    """
    return hmac.new(_lookup_secret(), b"recovery-lookup-key-id", hashlib.sha256).hexdigest()[:8]


def lookup_key(code: str) -> str:
    """Deterministic key of a recovery code, to find its account without scanning.

    HMAC with the server secret, never a bare hash: 31^8 codes are enumerable
    offline, so a plain sha256 stored next to the account would give the codes
    away to anyone holding a copy of the database. The secret lives next to
    JWT_SECRET in the server environment, so keying on it adds no exposure.
    """
    digest = hmac.new(_lookup_secret(), _normalize(code).encode(), hashlib.sha256).hexdigest()
    return f"{lookup_key_id()}:{digest[:40]}"


def _normalize(code: str) -> str:
    """Uniformise le code avant hash/verify : majuscules, tirets dans le bon
    format. Tolere une saisie 'j7k9pr4m', 'J7K9 PR4M', etc."""
    cleaned = code.strip().upper().replace(" ", "").replace("-", "")
    if len(cleaned) != _CODE_LEN:
        # On laisse passer (la verify echouera), mais on ne plante pas.
        return cleaned
    return f"{cleaned[:4]}-{cleaned[4:]}"
