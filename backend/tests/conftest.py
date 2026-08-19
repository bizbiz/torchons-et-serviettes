"""Fixtures pytest pour les tests backend T&S."""

from __future__ import annotations

import uuid

import httpx
import pytest
import pytest_asyncio
from sqlalchemy import select

from app.auth import create_token
from app.main import app

TEST_USER_ID = "1519d52e-1293-46da-92c0-e1be3e9f0808"
# Foyer dedie aux tests, UUID fixe : isole la suite de ce que la base
# contient par ailleurs (le foyer "Maison" seme par une migration, un foyer
# de dogfood, ...) et rend les runs reproductibles.
TEST_FOYER_ID = "0f0e0d0c-0b0a-4908-8706-050403020100"
DEVICE_A = str(uuid.uuid4())
DEVICE_B = str(uuid.uuid4())


@pytest_asyncio.fixture(scope="session")
async def client():
    """Client httpx qui parle directement a l'app FastAPI."""
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as c:
        yield c


@pytest.fixture(scope="session")
def auth_headers() -> dict:
    """Headers Authorization avec un token valide pour le user de test."""
    token = create_token(TEST_USER_ID, DEVICE_A)
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture(scope="session")
def device_a() -> str:
    return DEVICE_A


@pytest.fixture(scope="session")
def device_b() -> str:
    return DEVICE_B


@pytest.fixture(scope="session")
def auth_headers_device_b() -> dict:
    """Headers avec un device_id different (pour tester le filtrage self-device)."""
    token = create_token(TEST_USER_ID, DEVICE_B)
    return {"Authorization": f"Bearer {token}"}


@pytest_asyncio.fixture(scope="session", autouse=True)
async def seed_test_identity():
    """Cree l'user, le foyer et l'adhesion attendus par les tests d'API.

    Historiquement TEST_USER_ID existait "par hasard" dans la base de dev du
    poste, heritee de mois d'usage. Depuis que la base est un conteneur
    jetable, plus rien ne le cree : tous les tests d'API tombaient en 401
    ("Aucun foyer actif pour cet utilisateur"), y compris ceux de test_sync
    qui n'ont rien demande. Un test qui suppose un etat que personne ne
    produit n'est pas un test, c'est un rappel de ce qu'il y avait avant.

    Idempotent : ne cree que ce qui manque, ne remet rien a zero entre deux
    runs. Les tests mutent la base et l'assument (scope session).
    """
    from app.database import async_session
    from app.models.foyer import Foyer
    from app.models.user import User
    from app.models.user_foyer import UserFoyer

    user_uuid = uuid.UUID(TEST_USER_ID)
    foyer_uuid = uuid.UUID(TEST_FOYER_ID)

    async with async_session() as session:
        foyer = await session.get(Foyer, foyer_uuid)
        if foyer is None:
            session.add(Foyer(id=foyer_uuid, name="Foyer de test"))
            await session.flush()

        user = await session.get(User, user_uuid)
        if user is None:
            user = User(
                id=user_uuid,
                username="pytest",
                display_name="Pytest",
                last_active_foyer_id=foyer_uuid,
            )
            session.add(user)
        elif user.last_active_foyer_id is None:
            user.last_active_foyer_id = foyer_uuid
        await session.flush()

        membership = await session.execute(
            select(UserFoyer).where(
                UserFoyer.user_id == user_uuid,
                UserFoyer.foyer_id == foyer_uuid,
            )
        )
        if membership.scalar_one_or_none() is None:
            session.add(
                UserFoyer(user_id=user_uuid, foyer_id=foyer_uuid, role="admin")
            )

        await session.commit()

    yield
