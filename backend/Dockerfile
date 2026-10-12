# Image prod backend Torchons & Serviettes (FastAPI + Alembic).
#
# Build (depuis la racine repo) :
#   docker build -t ts-api ./backend
#
# Lance via docker-compose.prod.yml (db + api + caddy). L'app reste lancable
# en local hors Docker via la .venv (cf. CODING_CONVENTIONS.md) — cette image ne sert
# QUE pour le deploiement Scaleway public.
#
# Les deps sont extraites dynamiquement de pyproject.toml (pas de lockfile
# dans le repo) : zero duplication, zero drift. Le projet n'est PAS installe
# comme package (layout flat app/ + tests/ + alembic/ + seeds/), uvicorn
# tourne depuis les sources copiees dans /app.

FROM python:3.12-slim AS base

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

# 1. Deps uniquement (cache Docker invalide seulement si pyproject change).
COPY pyproject.toml ./
RUN python -c "import tomllib, pathlib; print('\n'.join(tomllib.loads(pathlib.Path('pyproject.toml').read_text())['project']['dependencies']))" > /tmp/requirements.txt \
 && pip install --no-cache-dir -r /tmp/requirements.txt

# 2. Code applicatif (cf. .dockerignore pour ce qui est exclu).
COPY . .

RUN chmod +x /app/docker-entrypoint.sh

# Not root. UID 1000 on purpose: the production host mounts firebase-key.json
# mode 600, owned by its `ubuntu` user, UID 1000 (measured on 2026-10-12). Any
# other UID would leave push notifications to fail silently.
RUN useradd --uid 1000 --no-create-home --shell /usr/sbin/nologin app
USER app

EXPOSE 8002

# Healthcheck aligne sur l'endpoint /api/health (status ok|degraded).
# The probe must give up on its own, BEFORE Docker's 5 s timeout: when Docker
# kills the probe it kills the `sh` wrapper, the python child is reparented to
# PID 1 (uvicorn) and never reaped -- 33 zombies on the beta between 18/09 and
# 21/09. 3 s leaves room for interpreter startup on a host under swap pressure.
# `init: true` in the compose files reaps whatever still slips through.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD python -c "import urllib.request,sys; r=urllib.request.urlopen('http://127.0.0.1:8002/api/health', timeout=3); sys.exit(0 if r.status==200 else 1)"

# L'entrypoint applique les migrations Alembic puis lance uvicorn.
ENTRYPOINT ["/app/docker-entrypoint.sh"]
