"""Fixtures pytest pour les tests backend T&S."""

from __future__ import annotations

import uuid

import httpx
import pytest
import pytest_asyncio

from app.auth import create_token
from app.main import app

USER_ID_QUENTIN = "***REMOVED***"
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
    """Headers Authorization avec un token valide pour Quentin."""
    token = create_token(USER_ID_QUENTIN, DEVICE_A)
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
    token = create_token(USER_ID_QUENTIN, DEVICE_B)
    return {"Authorization": f"Bearer {token}"}
