"""Async SQLAlchemy engine et session."""

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.config import settings

engine = create_async_engine(settings.database_url, echo=False)
async_session = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)


async def get_session():
    """Dependency FastAPI — yield une session async."""
    async with async_session() as session:
        yield session
