"""Helper to read the request locale from the Accept-Language header.

Used by endpoints that return localized strings (catalog templates,
in-memory labels). The backend supports a small whitelist of locales —
unknown / missing locales fall back to French.

Accept-Language parsing is intentionally simple: we look at the highest
priority token (first comma-separated entry) and check whether it starts
with a supported prefix. We don't honor q-values because the cost/benefit
isn't there yet for our 2-locale setup (fr/en).
"""

from __future__ import annotations

from fastapi import Header

# Locales supportées (data + UI). Premier = défaut/fallback.
SUPPORTED_LOCALES: tuple[str, ...] = ("fr", "en")


def _normalize(tag: str) -> str:
    """Normalize an Accept-Language tag to its primary subtag in lowercase.

    Examples:
      "fr-FR"   -> "fr"
      "EN-US"   -> "en"
      " fr_CA " -> "fr"
    """
    head = tag.strip().split(";", 1)[0].strip()
    primary = head.replace("_", "-").split("-", 1)[0].lower()
    return primary


def pick_locale_from_header(accept_language: str | None) -> str:
    """Return the best supported locale for the given Accept-Language value.

    Walks the comma-separated tokens left to right, returning the first
    one whose primary subtag matches SUPPORTED_LOCALES. Falls back to
    SUPPORTED_LOCALES[0] (= "fr") if nothing matches or the header is
    None / empty.
    """
    if not accept_language:
        return SUPPORTED_LOCALES[0]
    for tok in accept_language.split(","):
        primary = _normalize(tok)
        if primary in SUPPORTED_LOCALES:
            return primary
    return SUPPORTED_LOCALES[0]


def get_request_locale(
    accept_language: str | None = Header(default=None, alias="Accept-Language"),
) -> str:
    """FastAPI dependency: extract the locale from Accept-Language.

    Use it as `locale: str = Depends(get_request_locale)` in endpoint
    signatures. Returns one of SUPPORTED_LOCALES, defaulting to "fr".
    """
    return pick_locale_from_header(accept_language)
