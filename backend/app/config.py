"""Configuration via variables d'environnement (.env)."""

import os

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    model_config = {"env_file": os.path.join(os.path.dirname(__file__), "..", ".env")}

    # PostgreSQL
    database_url: str = "postgresql+asyncpg://tsapp:***REMOVED***@localhost:5435/torchons_serviettes"
    database_url_sync: str = "postgresql://tsapp:***REMOVED***@localhost:5435/torchons_serviettes"

    # Auth
    jwt_secret: str = "***REMOVED***"

    # Tailscale — mapping IP -> username
    tailscale_map: str = '{"REDACTED_TAILSCALE_IP":"alice","REDACTED_TAILSCALE_IP":"alice","127.0.0.1":"alice"}'

    # Backend
    backend_host: str = "0.0.0.0"
    backend_port: int = 8002


settings = Settings()
