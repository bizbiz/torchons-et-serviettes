"""Alembic env — charge les modeles pour autogenerate.

L'URL de la base est lue depuis `app.config.settings.database_url_sync`
(elle-meme provenant de `.env`), pas depuis `alembic.ini`. L'.ini ne
contient donc plus de credentials.
"""

from logging.config import fileConfig

from alembic import context
from sqlalchemy import engine_from_config, pool

from app.config import settings
from app.models import Base

config = context.config
if config.config_file_name is not None:
    fileConfig(config.config_file_name)

# Override la URL fournie par alembic.ini avec celle de .env (centralisee).
config.set_main_option("sqlalchemy.url", settings.database_url_sync)

target_metadata = Base.metadata


def run_migrations_online() -> None:
    connectable = engine_from_config(
        config.get_section(config.config_ini_section, {}),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )
    with connectable.connect() as connection:
        context.configure(connection=connection, target_metadata=target_metadata)
        with context.begin_transaction():
            context.run_migrations()


run_migrations_online()
