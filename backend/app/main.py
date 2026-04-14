"""Torchons & Serviettes — entrypoint FastAPI.

Lance avec :
    uvicorn app.main:app --host 0.0.0.0 --port 8002
"""

from contextlib import asynccontextmanager

import structlog
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy import text

from app.database import engine

logger = structlog.get_logger()


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("T&S backend starting", port=8002)
    yield
    await engine.dispose()
    logger.info("T&S backend stopped")


from app.api.content import APP_VERSION

app = FastAPI(title="Torchons & Serviettes", version=APP_VERSION, lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # Android app — toutes origines
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Routers
from app.api.auth import router as auth_router
from app.api.tasks import router as tasks_router
from app.api.categories import router as categories_router
from app.api.rooms import router as rooms_router
from app.api.foyers import router as foyers_router
from app.api.presence import router as presence_router
from app.api.projection import router as projection_router
from app.api.propositions import router as propositions_router
from app.api.completions import router as completions_router
from app.api.gamification import router as gamification_router
from app.api.stats import router as stats_router
from app.api.notifications import router as notifications_router
from app.api.settings import router as settings_router
from app.api.content import router as content_router
from app.api.sync import router as sync_router

app.include_router(auth_router)
app.include_router(tasks_router)
app.include_router(categories_router)
app.include_router(rooms_router)
app.include_router(foyers_router)
app.include_router(presence_router)
app.include_router(projection_router)
app.include_router(propositions_router)
app.include_router(completions_router)
app.include_router(gamification_router)
app.include_router(stats_router)
app.include_router(notifications_router)
app.include_router(settings_router)
app.include_router(content_router)
app.include_router(sync_router)


@app.get("/api/health")
async def health():
    """Health check endpoint for external monitoring."""
    try:
        async with engine.connect() as conn:
            await conn.execute(text("SELECT 1"))
        db_status = "ok"
    except Exception as e:
        db_status = f"error: {e}"
    return {"status": "ok" if db_status == "ok" else "degraded", "database": db_status, "version": APP_VERSION}
