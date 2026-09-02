"""Devices holding a valid session for a user, and their revocation state.

Why this table exists
---------------------
The session JWT carries no `exp` claim and the server holds no revocation
list, so a token stays valid until `jwt_secret` itself is rotated -- which
would sign every household member out at once. This table makes revocation
GRANULAR: one row per (user, device), and `revoked_at` closes exactly one
door.

The `device_id` is generated client-side and lives, on Android, in a
DataStore deliberately EXCLUDED from Auto Backup. That exclusion is what
makes two phones distinguishable after a restore: the restored phone finds
no identity, mints a new one, and shows up here as its own row while the
old phone keeps its own. See `docs/appareils.fr.md`.

Scope: a user only ever sees and revokes their OWN devices. `role`
(admin/member) is not consulted -- the product has no asymmetric power
between household members, and this is not the feature that should
introduce one.
"""

from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, String, UniqueConstraint, func
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, UUIDMixin


class Device(Base, UUIDMixin):
    __tablename__ = "devices"
    # A device_id is minted per installation, so it is unique per user. The
    # pair is what a revocation targets.
    __table_args__ = (UniqueConstraint("user_id", "device_id"),)

    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    # Client-minted UUID. Stored as text: it comes from the JWT and from a
    # header, and we never want a malformed value to raise before the auth
    # layer can answer 401 cleanly.
    device_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    # `Build.MODEL` ("Pixel 7"). Never unique: the same household often runs
    # the same model twice, which is why the UI leans on first_seen /
    # last_seen and on a "this device" marker rather than on the name.
    device_name: Mapped[str | None] = mapped_column(String(200), nullable=True)
    platform: Mapped[str] = mapped_column(String(20), default="android", nullable=False)

    first_seen: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    # Throttled to at most one write per hour per device: this is displayed
    # twice a year, it must not cost a write per request.
    last_seen: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    # Non-null = access cut. Kept as a row rather than deleted, so the
    # screen can still show "revoked on <date>" instead of a device
    # silently vanishing from the list.
    revoked_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
