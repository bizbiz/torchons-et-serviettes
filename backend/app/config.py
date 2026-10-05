"""Configuration via variables d'environnement (.env).

Toutes les valeurs sensibles (URL DB, secret JWT) sont
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

    # Backend
    backend_host: str = "0.0.0.0"
    backend_port: int = 8002

    # Auto-archivage (v0.20) : une tache ponctuelle (sans recurrence) completee
    # depuis plus de ARCHIVE_AFTER_DAYS jours est automatiquement archivee
    # par un job quotidien (is_archived=true, filtree des lectures liste).
    archive_after_days: int = 7

    # Version gate (see app/utils/version_gate.py). Below this Android
    # versionCode, data routes answer 426 and the app blocks itself.
    #
    # 0 disables the gate: that is the default, and it is what makes deploying
    # this code a no-op until a threshold is set on purpose. Raising it locks
    # out every phone below it at once, and APKs here are sideloaded -- there is
    # no store to push the fix through -- so MIN_APP_DOWNLOAD_URL must say where
    # to get the new build BEFORE the threshold is raised.
    #
    # Deliberately NOT derived from APP_VERSION: that string is copied by hand
    # between machines and has already lied in both directions.
    min_app_version_code: int = 0
    min_app_download_url: str = ""


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
