"""Seed data — cree les 2 utilisateurs et les categories par defaut."""

from __future__ import annotations

import asyncio

from sqlalchemy import select, text

from app.database import async_session, engine
from app.models import Base
from app.models.user import User
from app.models.category import Category
from app.models.fair_share import FairShareConfig


USERS = [
    {"username": "alice", "display_name": "Quentin"},
    {"username": "bob", "display_name": "***REMOVED***"},
]

CATEGORIES = [
    {"name": "Ménage", "color": "#10B981", "icon": "broom", "sort_order": 0},
    {"name": "Courses", "color": "#F59E0B", "icon": "cart", "sort_order": 1},
    {"name": "Admin", "color": "#6366F1", "icon": "file", "sort_order": 2},
    {"name": "Perso", "color": "#EC4899", "icon": "user", "sort_order": 3},
    {"name": "Maison", "color": "#8B5CF6", "icon": "home", "sort_order": 4},
]


async def run_seed():
    """Insere les donnees de base si elles n'existent pas."""
    async with async_session() as session:
        # Users
        user_ids = {}
        for u in USERS:
            existing = await session.execute(select(User).where(User.username == u["username"]))
            if existing.scalar_one_or_none():
                print(f"  User '{u['username']}' existe deja")
                result = await session.execute(select(User).where(User.username == u["username"]))
                user_ids[u["username"]] = result.scalar_one().id
                continue
            user = User(**u)
            session.add(user)
            await session.flush()
            user_ids[u["username"]] = user.id
            print(f"  + User '{u['username']}' cree")

        # Categories
        for c in CATEGORIES:
            existing = await session.execute(select(Category).where(Category.name == c["name"]))
            if existing.scalar_one_or_none():
                print(f"  Category '{c['name']}' existe deja")
                continue
            session.add(Category(**c))
            print(f"  + Category '{c['name']}' creee")

        # Fair share config (60/40 par defaut)
        for username, ratio in [("alice", 0.6), ("bob", 0.4)]:
            uid = user_ids.get(username)
            if not uid:
                continue
            existing = await session.execute(select(FairShareConfig).where(FairShareConfig.user_id == uid))
            if existing.scalar_one_or_none():
                continue
            session.add(FairShareConfig(user_id=uid, target_ratio=ratio))
            print(f"  + FairShare {username} = {int(ratio*100)}%")

        await session.commit()
        print("Seed termine.")


if __name__ == "__main__":
    asyncio.run(run_seed())
