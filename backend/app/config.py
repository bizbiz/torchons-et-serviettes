"""Configuration via variables d'environnement (.env).

Toutes les valeurs sensibles (URL DB, secret JWT, mapping Tailscale) sont
chargees depuis `backend/.env` (gitignore). Le fichier `.env.example` du repo
sert de template avec des valeurs `CHANGE_ME` a remplacer.

Au demarrage, si une valeur sensible est vide, on leve une erreur explicite
plutot que de laisser le backend tourner avec des defaults non securises.
"""

import os

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    # `.env` est a la racine du projet (partage avec docker-compose), pas dans backend/.
    # `extra=ignore` : les variables `POSTGRES_*` (utilisees par docker-compose) sont
    # ignorees ici, on lit uniquement DATABASE_URL / DATABASE_URL_SYNC.
    model_config = {
        "env_file": os.path.join(os.path.dirname(__file__), "..", "..", ".env"),
        "extra": "ignore",
    }

    # PostgreSQL — lus depuis .env, vide par defaut.
    database_url: str = ""
    database_url_sync: str = ""

    # Auth — secret JWT, lu depuis .env, vide par defaut.
    jwt_secret: str = ""

    # Tailscale — mapping JSON {"ip":"username"} pour l'auto-login zero-config.
    # Vide par defaut : pas d'auto-login si non configure.
    tailscale_map: str = "{}"

    # Backend
    backend_host: str = "0.0.0.0"
    backend_port: int = 8002


settings = Settings()


def _validate_required() -> None:
    """Echoue au demarrage si une variable sensible n'est pas configuree."""
    missing = []
    if not settings.database_url:
        missing.append("DATABASE_URL")
    if not settings.database_url_sync:
        missing.append("DATABASE_URL_SYNC")
    if not settings.jwt_secret:
        missing.append("JWT_SECRET")
    if missing:
        raise RuntimeError(
            "Configuration manquante dans backend/.env : "
            + ", ".join(missing)
            + ". Voir backend/.env.example pour le format attendu."
        )


_validate_required()
