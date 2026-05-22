"""Routes de contenu statique : notes de version et roadmap in-app.

Sert les fichiers markdown versionnes dans `app/content/`. Les clients
recoivent le markdown brut et le rendent eux-memes.
"""

from __future__ import annotations

import re
from pathlib import Path

from fastapi import APIRouter, Depends

from app.auth import get_current_user
from app.utils.errors import LocalizedHTTPException

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


def _read_markdown(filename: str) -> str:
    """Lit un fichier markdown du dossier content/, ou 404 s'il manque."""
    path = _CONTENT_DIR / filename
    if not path.is_file():
        raise LocalizedHTTPException(
            status_code=404,
            error_code="CONTENT_FILE_NOT_FOUND_FORMAT",
            format_args={"filename": filename},
        )
    return path.read_text(encoding="utf-8")


@router.get("/changelog")
async def get_changelog(_user: dict = Depends(get_current_user)):
    """Retourne le changelog de l'app en markdown."""
    return {
        "version": APP_VERSION,
        "format": "markdown",
        "content": _read_markdown("changelog.md"),
    }


@router.get("/roadmap")
async def get_roadmap(_user: dict = Depends(get_current_user)):
    """Retourne la roadmap de l'app en markdown."""
    return {
        "format": "markdown",
        "content": _read_markdown("roadmap.md"),
    }
