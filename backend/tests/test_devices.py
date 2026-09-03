"""Devices: listing, silent transition, and revocation.

INVARIANT UNDER TEST
    A revoked device no longer reaches household data.

CHEMINS PAR LESQUELS CET INVARIANT PEUT ETRE VIOLE
Enumeres mecaniquement avant d'ecrire le premier cas : le controle vit dans
`resolve_device`, appele par `get_current_user`, donc tout acces qui ne
traverse pas cette dependance echappe au controle.

  (1) Route authentifiee ordinaire .................... COUVERT
      Toutes les routes de donnees dependent de `get_current_user`. Balayage :
      un seul module expose des routes sans aucun appel a `get_current_user`
      (`onboarding.py`), et il ne sert aucune donnee de foyer.

  (2) Re-adoption d'identite par un appareil revoque .. COUVERT
      Le chemin le plus dangereux : si `/devices/register` adoptait un
      device_id neuf presente avec un jeton revoque, la revocation serait
      decorative. Teste par `test_revoked_device_cannot_adopt_new_identity`.

  (3) Revocation de soi-meme via /revoke ............. COUVERT
      Doit passer par /me/signout, qui declenche l'effacement local cote
      client. Teste.

  (4) Jeton LEGACY sans claim `device_id` ............ NON COUVERT, assume
      `get_current_user` n'appelle alors `resolve_device` sur rien : ces
      sessions ne sont pas revocables. C'est deliberé — le defaut historique
      fabriquait un UUID neuf a chaque appel, donc les brancher creerait une
      ligne `devices` PAR REQUETE. Ces jetons datent d'avant v0.7.0 ; le
      remede, si le cas se presente, est une rotation de `jwt_secret`.

  (5) Notifications push apres revocation ............ COUVERT (03/09)
      `notification_tokens` porte desormais un `device_id`, rempli depuis le
      JWT — donc sans attendre une mise a jour de l'app, et sans qu'un client
      puisse mentir dessus. Les trois chemins de revocation suppriment les
      jetons FCM de l'appareil coupe. Restent hors portee les lignes creees
      AVANT cette colonne (`device_id` NULL, rattachees a personne) : elles
      se rattachent d'elles-memes au prochain enregistrement FCM.

  (6) Cache local deja sur l'appareil ................ HORS PORTEE SERVEUR
      Les 7 tables Room survivent a la revocation jusqu'au premier contact
      reseau. C'est le client qui les efface, sur le code `DEVICE_REVOKED`
      — jamais sur un 401 ordinaire. Couvert cote Android, pas ici.

Tout device_id utilise ici est cree DANS le test : revoquer DEVICE_A tuerait
`auth_headers` pour toute la suite, dont les fixtures sont a portee session
et la base mutee volontairement.
"""

from __future__ import annotations

import uuid

import pytest
import pytest_asyncio

from app.auth import create_token
from tests.conftest import TEST_FOYER_ID, TEST_USER_ID


@pytest_asyncio.fixture(autouse=True)
async def undo_revocations():
    """Remet a NULL les revocations laissees par CE fichier.

    `/recovery/regenerate` revoque TOUS les autres appareils du user, et
    toute la suite partage TEST_USER_ID : sans ce nettoyage, un seul test
    d'ici met 17 tests de test_sync en 401. Mesure du 2026-09-02 : 60 lignes
    revoquees, 17 rouges ailleurs. Le teardown tourne apres les assertions,
    qui restent donc valides.
    """
    yield
    from sqlalchemy import update

    from app.database import async_session
    from app.models.device import Device

    async with async_session() as session:
        await session.execute(
            update(Device)
            .where(Device.user_id == uuid.UUID(TEST_USER_ID))
            .values(revoked_at=None)
        )
        await session.commit()


def _headers(device_id: str) -> dict:
    token = create_token(TEST_USER_ID, device_id, TEST_FOYER_ID)
    return {"Authorization": f"Bearer {token}"}


@pytest.mark.asyncio
async def test_unknown_device_is_adopted_not_refused(client):
    """Chemin (1), sens nominal : un jeton d'avant cette table marche encore."""
    device = str(uuid.uuid4())
    r = await client.get("/api/devices", headers=_headers(device))
    assert r.status_code == 200, r.text
    ids = [d["device_id"] for d in r.json()]
    assert device in ids, "the calling device should have been adopted"


@pytest.mark.asyncio
async def test_current_device_is_flagged(client):
    device = str(uuid.uuid4())
    r = await client.get("/api/devices", headers=_headers(device))
    assert r.status_code == 200
    current = [d for d in r.json() if d["is_current"]]
    assert len(current) == 1
    assert current[0]["device_id"] == device


@pytest.mark.asyncio
async def test_register_same_id_returns_no_new_token(client):
    device = str(uuid.uuid4())
    r = await client.post(
        "/api/devices/register",
        headers=_headers(device),
        json={"device_id": device, "device_name": "Pixel 7"},
    )
    assert r.status_code == 200, r.text
    assert r.json()["new_token"] is None
    listed = await client.get("/api/devices", headers=_headers(device))
    named = [d for d in listed.json() if d["device_id"] == device]
    assert named and named[0]["device_name"] == "Pixel 7"


@pytest.mark.asyncio
async def test_restore_gets_a_new_identity_silently(client):
    """La transition silencieuse : jeton restaure + device_id neuf."""
    old_device = str(uuid.uuid4())
    new_device = str(uuid.uuid4())
    await client.get("/api/devices", headers=_headers(old_device))

    r = await client.post(
        "/api/devices/register",
        headers=_headers(old_device),
        json={"device_id": new_device, "device_name": "Pixel 9"},
    )
    assert r.status_code == 200, r.text
    assert r.json()["new_token"], "a rebound token was expected"

    listed = await client.get("/api/devices", headers=_headers(old_device))
    ids = [d["device_id"] for d in listed.json()]
    assert old_device in ids and new_device in ids


@pytest.mark.asyncio
async def test_revoked_device_is_refused_with_its_own_code(client):
    """Chemin (1) en negatif, et le code doit etre DISTINCT d'un 401 nu."""
    victim = str(uuid.uuid4())
    keeper = str(uuid.uuid4())
    await client.get("/api/devices", headers=_headers(victim))
    await client.get("/api/devices", headers=_headers(keeper))

    r = await client.post(f"/api/devices/{victim}/revoke", headers=_headers(keeper))
    assert r.status_code == 204, r.text

    refused = await client.get("/api/devices", headers=_headers(victim))
    assert refused.status_code == 401
    detail = refused.json()["detail"]
    assert "voqu" in detail or "revok" in detail, f"code indistinct d'un 401 nu : {detail}"


@pytest.mark.asyncio
async def test_revoked_device_cannot_adopt_new_identity(client):
    """Chemin (2) — sans ce test, la revocation serait decorative."""
    victim = str(uuid.uuid4())
    keeper = str(uuid.uuid4())
    await client.get("/api/devices", headers=_headers(victim))
    await client.get("/api/devices", headers=_headers(keeper))
    await client.post(f"/api/devices/{victim}/revoke", headers=_headers(keeper))

    escape = await client.post(
        "/api/devices/register",
        headers=_headers(victim),
        json={"device_id": str(uuid.uuid4()), "device_name": "Nice try"},
    )
    assert escape.status_code == 401, "a revoked token must not mint a new device"


@pytest.mark.asyncio
async def test_cannot_revoke_self_through_revoke(client):
    """Chemin (3)."""
    device = str(uuid.uuid4())
    await client.get("/api/devices", headers=_headers(device))
    r = await client.post(f"/api/devices/{device}/revoke", headers=_headers(device))
    assert r.status_code == 400


@pytest.mark.asyncio
async def test_signout_revokes_the_calling_device(client):
    device = str(uuid.uuid4())
    await client.get("/api/devices", headers=_headers(device))
    r = await client.post("/api/devices/me/signout", headers=_headers(device))
    assert r.status_code == 204, r.text
    after = await client.get("/api/devices", headers=_headers(device))
    assert after.status_code == 401


@pytest.mark.asyncio
async def test_regenerate_recovery_code_cuts_the_other_devices(client):
    keeper = str(uuid.uuid4())
    other = str(uuid.uuid4())
    await client.get("/api/devices", headers=_headers(keeper))
    await client.get("/api/devices", headers=_headers(other))

    r = await client.post("/api/recovery/regenerate", headers=_headers(keeper))
    assert r.status_code == 200, r.text
    assert r.json()["revoked_devices"] >= 1

    assert (await client.get("/api/devices", headers=_headers(other))).status_code == 401
    # Celui qui clique reste dedans : sinon on se met dehors avec le bouton
    # cense proteger.
    assert (await client.get("/api/devices", headers=_headers(keeper))).status_code == 200


# --- Notifications push : elles suivent la revocation (chemin 5) ---


async def _register_push(client, device: str, fcm: str):
    return await client.post(
        "/api/notifications/register",
        headers=_headers(device),
        json={"fcm_token": fcm, "device_name": "Pixel 7", "platform": "android"},
    )


async def _push_tokens_of(device: str) -> int:
    """Compte les jetons FCM rattaches a cet appareil, en base."""
    from sqlalchemy import func, select

    from app.database import async_session
    from app.models.notification import NotificationToken

    async with async_session() as session:
        return (
            await session.execute(
                select(func.count())
                .select_from(NotificationToken)
                .where(NotificationToken.device_id == device)
            )
        ).scalar_one()


@pytest.mark.asyncio
async def test_push_token_is_bound_to_the_calling_device(client):
    """Le lien vient du JWT : aucun changement d'app n'est requis pour l'obtenir."""
    device = str(uuid.uuid4())
    r = await _register_push(client, device, f"fcm-{device}")
    assert r.status_code == 200, r.text
    assert await _push_tokens_of(device) == 1


@pytest.mark.asyncio
async def test_revoking_a_device_drops_its_push_tokens(client):
    """Chemin (5) : couper l'API sans couper les notifications ne coupait rien."""
    victim = str(uuid.uuid4())
    keeper = str(uuid.uuid4())
    await _register_push(client, victim, f"fcm-{victim}")
    await _register_push(client, keeper, f"fcm-{keeper}")
    assert await _push_tokens_of(victim) == 1

    r = await client.post(f"/api/devices/{victim}/revoke", headers=_headers(keeper))
    assert r.status_code == 204, r.text

    assert await _push_tokens_of(victim) == 0, "le telephone coupe recevrait encore"
    assert await _push_tokens_of(keeper) == 1, "celui qui reste ne doit pas etre touche"


@pytest.mark.asyncio
async def test_signout_drops_its_own_push_tokens(client):
    device = str(uuid.uuid4())
    await _register_push(client, device, f"fcm-{device}")
    r = await client.post("/api/devices/me/signout", headers=_headers(device))
    assert r.status_code == 204, r.text
    assert await _push_tokens_of(device) == 0


@pytest.mark.asyncio
async def test_regenerate_drops_the_other_devices_push_tokens(client):
    keeper = str(uuid.uuid4())
    other = str(uuid.uuid4())
    await _register_push(client, keeper, f"fcm-{keeper}")
    await _register_push(client, other, f"fcm-{other}")

    r = await client.post("/api/recovery/regenerate", headers=_headers(keeper))
    assert r.status_code == 200, r.text

    assert await _push_tokens_of(other) == 0
    assert await _push_tokens_of(keeper) == 1, "celui qui a clique reste joignable"
