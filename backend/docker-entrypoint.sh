#!/usr/bin/env sh
# Entrypoint conteneur prod : migrations Alembic PUIS uvicorn.
#
# Alembic lit settings.database_url_sync via app.config (env var
# DATABASE_URL_SYNC injectee par docker-compose). On applique head a chaque
# boot : idempotent, et garantit que le schema suit le code deploye.
#
# `exec` pour que uvicorn recoive les signaux (SIGTERM) du conteneur et
# s'arrete proprement.
set -e

echo "[entrypoint] alembic upgrade head..."
python -m alembic upgrade head

echo "[entrypoint] starting uvicorn on :8002..."
# --no-access-log: uvicorn would log every request with the client IP (taken
# from X-Forwarded-For). Caddy already keeps an access log, so a second copy of
# the IPs only widens what must be retained and bounded.
exec python -m uvicorn app.main:app --host 0.0.0.0 --port 8002 --no-access-log
