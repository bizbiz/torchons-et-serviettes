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

EXPOSE 8002

# Healthcheck aligne sur l'endpoint /api/health (status ok|degraded).
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD python -c "import urllib.request,sys; r=urllib.request.urlopen('http://127.0.0.1:8002/api/health'); sys.exit(0 if r.status==200 else 1)"

# L'entrypoint applique les migrations Alembic puis lance uvicorn.
ENTRYPOINT ["/app/docker-entrypoint.sh"]
