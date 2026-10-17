"""Device resolution on every authenticated call: revocation, and last_seen.

Three rules, each paid for by a measurement or an incident.

1. A REVOKED device is refused, and refused with a code of its own
   (`DEVICE_REVOKED`), never a bare 401. The client erases its local cache
   on that code; a plain 401 -- expired token, auth outage, clock skew --
   must never erase anything. Same lesson as the version gate: a refusal
   that means something specific needs its own signal.

2. An UNKNOWN device_id is adopted, not refused. Every phone already in the
   field carries a token minted before this table existed; refusing them
   would sign the whole household out the moment this ships. This is the
   anti-brick rule of `docs/version-gate.fr.md`, applied here.

3. `last_seen` is written at most once an hour per device. It is displayed
   twice a year; it must not cost a write per request.

Security note, and it is the load-bearing one: adoption applies to an
UNKNOWN device_id, never to a REVOKED one. Otherwise a revoked phone would
just mint a fresh identity and re-issue itself access, and revocation
would be decorative. Revocation therefore targets the device_id carried BY
THE TOKEN, and a revoked token dies there -- no transition, no adoption.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.device import Device
from app.utils.errors import LocalizedHTTPException

# How stale `last_seen` may get before we spend a write on it.
LAST_SEEN_THROTTLE = timedelta(hours=1)


async def _adopt_device(
    session: AsyncSession,
    uid: uuid.UUID,
    device_id: str,
    device_name: str | None,
    now: datetime,
) -> Device:
    """Register an unknown device_id (rule 2) and return its row.

    `first_seen` is now and not "unknown": for a device already in the field it
    means "first seen by this mechanism", which is the honest reading and the one
    the screen shows. ON CONFLICT DO NOTHING: a new phone sends several requests at
    once, and with a plain insert the second adoption failed on
    uq_devices_user_device, a 500 (found 2026-10-17 by
    tests/test_settings_first_read_race.py). The loser reads the winner's row.
    """
    await session.execute(
        pg_insert(Device).values(
            id=uuid.uuid4(),
            user_id=uid,
            device_id=device_id,
            device_name=device_name,
            first_seen=now,
            last_seen=now,
        ).on_conflict_do_nothing(index_elements=[Device.user_id, Device.device_id])
    )
    # COMMIT, pas seulement flush : cette fonction tourne dans une
    # dependance d'authentification, avant le corps de la route. Sans
    # commit la ligne meurt avec la transaction et l'appareil est
    # re-adopte a chaque appel — donc jamais revocable. Mesure : les
    # trois tests de revocation echouaient en 404 sans cette ligne.
    await session.commit()
    return (
        await session.execute(
            select(Device).where(Device.user_id == uid, Device.device_id == device_id)
        )
    ).scalar_one()


async def resolve_device(
    session: AsyncSession,
    user_id: str,
    device_id: str,
    device_name: str | None = None,
) -> Device:
    """Return the Device row for this session, refusing it if revoked.

    Adopts the device when it is unknown (pre-existing installs), and
    refreshes `last_seen` at most once per `LAST_SEEN_THROTTLE`.
    """
    uid = uuid.UUID(user_id)
    row = (
        await session.execute(
            select(Device).where(Device.user_id == uid, Device.device_id == device_id)
        )
    ).scalar_one_or_none()

    if row is not None and row.revoked_at is not None:
        raise LocalizedHTTPException(status_code=401, error_code="DEVICE_REVOKED")

    now = datetime.now(timezone.utc)

    if row is None:
        return await _adopt_device(session, uid, device_id, device_name, now)

    dirty = False
    if device_name and row.device_name != device_name:
        row.device_name = device_name
        dirty = True

    if row.last_seen is None or (now - row.last_seen) > LAST_SEEN_THROTTLE:
        row.last_seen = now
        dirty = True

    # Throttle : hors adoption, on n'ecrit que si quelque chose a bouge.
    if dirty:
        await session.commit()

    return row
