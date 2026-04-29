"""Torchons & Serviettes — entrypoint FastAPI.

Lance avec :
    uvicorn app.main:app --host 0.0.0.0 --port 8002
"""

from contextlib import asynccontextmanager

import structlog
from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.cron import CronTrigger
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy import text

from app.database import engine
from app.services.archiving import daily_archive_job
from app.services.retard_history import daily_retard_job

logger = structlog.get_logger()

# Scheduler unique process-wide. Declare ici pour pouvoir le shutdown dans
# lifespan. Configure avec CronTrigger UTC (cf timezone handling du backend).
_scheduler: AsyncIOScheduler | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global _scheduler
    logger.info("T&S backend starting", port=8002)

    # Job quotidien : auto-archive des taches ponctuelles completees depuis
    # plus de settings.archive_after_days jours. 03h00 UTC = creux d'activite
    # (05h Paris ete, 04h Paris hiver). Si le backend est down a cette heure,
    # APScheduler ne retente pas automatiquement : on accepte un archivage
    # decale d'un jour, sans consequence fonctionnelle.
    _scheduler = AsyncIOScheduler(timezone="UTC")
    _scheduler.add_job(
        daily_archive_job,
        CronTrigger(hour=3, minute=0),
        id="daily_archive",
        replace_existing=True,
    )
    # Snapshot retard du foyer : 15 min apres l'archivage pour etaler la
    # charge (cron sequentiel foyer par foyer cf. retard_history.py).
    _scheduler.add_job(
        daily_retard_job,
        CronTrigger(hour=3, minute=15),
        id="daily_retard_snapshot",
        replace_existing=True,
    )
    _scheduler.start()
    logger.info(
        "scheduler_started",
        jobs=["daily_archive@03:00 UTC", "daily_retard_snapshot@03:15 UTC"],
    )

    yield

    if _scheduler is not None and _scheduler.running:
        _scheduler.shutdown(wait=False)
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
from app.api.catalog import router as catalog_router
from app.api.completions import router as completions_router
from app.api.gamification import router as gamification_router
from app.api.stats import router as stats_router
from app.api.notifications import router as notifications_router
from app.api.settings import router as settings_router
from app.api.content import router as content_router
from app.api.sync import router as sync_router
from app.api.bonus_config import router as bonus_config_router
from app.api.prompts import router as prompts_router
from app.api.onboarding import router as onboarding_router
from app.api.pairing import router as pairing_router
from app.api.recovery import router as recovery_router
from app.api.me import router as me_router

app.include_router(auth_router)
app.include_router(tasks_router)
app.include_router(categories_router)
app.include_router(rooms_router)
app.include_router(foyers_router)
app.include_router(presence_router)
app.include_router(projection_router)
app.include_router(propositions_router)
app.include_router(catalog_router)
app.include_router(completions_router)
app.include_router(gamification_router)
app.include_router(stats_router)
app.include_router(notifications_router)
app.include_router(settings_router)
app.include_router(content_router)
app.include_router(sync_router)
app.include_router(bonus_config_router)
app.include_router(prompts_router)
app.include_router(onboarding_router)
app.include_router(pairing_router)
app.include_router(recovery_router)
app.include_router(me_router)


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
