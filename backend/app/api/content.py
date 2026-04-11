"""Routes de contenu statique : notes de version et roadmap in-app.

Sert les fichiers markdown versionnes dans `app/content/`. Les clients
recoivent le markdown brut et le rendent eux-memes.
"""

from __future__ import annotations

from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException

from app.auth import get_current_user

router = APIRouter(prefix="/api", tags=["content"])

# Chemin absolu vers le dossier des contenus statiques.
_CONTENT_DIR = Path(__file__).resolve().parent.parent / "content"


# Version courante de l'app cote backend. A bumper en meme temps que
# l'ajout d'une nouvelle section dans changelog.md.
APP_VERSION = "0.2.1"


def _read_markdown(filename: str) -> str:
    """Lit un fichier markdown du dossier content/, ou 404 s'il manque."""
    path = _CONTENT_DIR / filename
    if not path.is_file():
        raise HTTPException(status_code=404, detail=f"Fichier introuvable : {filename}")
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
