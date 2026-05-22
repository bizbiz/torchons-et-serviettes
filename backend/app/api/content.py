"""Routes de contenu statique : notes de version et roadmap in-app.

Sert les fichiers markdown versionnes dans `app/content/`. Les clients
recoivent le markdown brut et le rendent eux-memes.

i18n (Lot 4 post-launch) : si le client envoie `Accept-Language: en`,
on sert le `.en.md` correspondant. Fallback automatique sur le `.md`
FR si la version EN n'existe pas (compat ancienne).
"""

from __future__ import annotations

import re
from pathlib import Path

from fastapi import APIRouter, Depends, Request

from app.auth import get_current_user
from app.utils.errors import LocalizedHTTPException
from app.utils.locale import pick_locale_from_header

router = APIRouter(prefix="/api", tags=["content"])

# Chemin absolu vers le dossier des contenus statiques.
_CONTENT_DIR = Path(__file__).resolve().parent.parent / "content"


def _read_app_version() -> str:
    """Derive la version courante depuis la premiere entree du changelog.

    Source unique de verite : la ligne `## vX.Y.Z - YYYY-MM-DD` la plus
    haute dans `changelog.md`. Evite la duplication d'une constante a
    bumper a la main a chaque release.

    Si le fichier est absent ou mal forme, retourne `0.0.0` pour ne pas
    casser le boot du backend.
    """
    try:
        text = (_CONTENT_DIR / "changelog.md").read_text(encoding="utf-8")
        match = re.search(r"^##\s+v(\d+\.\d+\.\d+)", text, re.MULTILINE)
        if match:
            return match.group(1)
    except OSError:
        pass
    return "0.0.0"


# Version courante exposee via /api/health, /api/changelog et la title
# FastAPI. Calculee une fois au boot a partir du changelog.
APP_VERSION = _read_app_version()


def _localized_filename(base: str, locale: str) -> str:
    """Retourne le nom de fichier pour la locale demandee.

    Convention : `<base>.md` = FR (defaut, ne pas casser), `<base>.en.md` = EN.
    Le caller verifie l'existence et fallback sur FR si EN absent.
    """
    if locale == "en":
        # base = "changelog" -> "changelog.en.md"
        return f"{base}.en.md"
    return f"{base}.md"


def _read_markdown_localized(base: str, locale: str) -> str:
    """Lit le markdown du dossier content/ pour `base` dans `locale`.

    Tente d'abord `<base>.<locale>.md`. Si absent, fallback sur
    `<base>.md` (FR). Si les deux absents, 404.
    """
    primary = _CONTENT_DIR / _localized_filename(base, locale)
    fallback = _CONTENT_DIR / f"{base}.md"
    path = primary if primary.is_file() else fallback
    if not path.is_file():
        raise LocalizedHTTPException(
            status_code=404,
            error_code="CONTENT_FILE_NOT_FOUND_FORMAT",
            format_args={"filename": path.name},
        )
    return path.read_text(encoding="utf-8")


@router.get("/changelog")
async def get_changelog(
    request: Request,
    _user: dict = Depends(get_current_user),
):
    """Retourne le changelog de l'app en markdown, localise via Accept-Language."""
    locale = pick_locale_from_header(request.headers.get("accept-language"))
    return {
        "version": APP_VERSION,
        "format": "markdown",
        "locale": locale,
        "content": _read_markdown_localized("changelog", locale),
    }


@router.get("/roadmap")
async def get_roadmap(
    request: Request,
    _user: dict = Depends(get_current_user),
):
    """Retourne la roadmap de l'app en markdown, localise via Accept-Language."""
    locale = pick_locale_from_header(request.headers.get("accept-language"))
    return {
        "format": "markdown",
        "locale": locale,
        "content": _read_markdown_localized("roadmap", locale),
    }
