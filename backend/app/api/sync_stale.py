"""Staleness rule of a /sync UPDATE: was the row changed by someone else after the op was queued?"""

from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import exists, func, or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.sync import ChangeLog, ProcessedOp


def truncate_to_ms(dt: datetime) -> datetime:
    """Truncate a datetime to the millisecond (Android resolution)."""
    return dt.replace(microsecond=(dt.microsecond // 1000) * 1000)


async def last_foreign_change_at(
    session: AsyncSession, table: str, row_id: uuid.UUID, device_id: uuid.UUID
) -> datetime | None:
    """When a writer OTHER than this device last changed the row, from change_log; None if never.

    A change is this device's own when its `client_op_id` is in `processed_ops` for `device_id`.
    Everything else counts: a REST write or a system job (`client_op_id` NULL), and an op of
    another device. The row's `updated_at` cannot serve: the server stamps it at write time, so
    it also holds this device's own earlier ops of the same push (an edit queued after an
    offline create, two edits of one task), which were queued BEFORE the push that wrote them.
    """
    own_op = exists().where(
        ProcessedOp.client_op_id == ChangeLog.client_op_id,
        ProcessedOp.device_id == device_id,
    )
    return (
        await session.execute(
            select(func.max(ChangeLog.changed_at)).where(
                ChangeLog.table_name == table,
                ChangeLog.row_id == row_id,
                or_(ChangeLog.client_op_id.is_(None), ~own_op),
            )
        )
    ).scalar()


async def is_stale(
    session: AsyncSession, table: str, row_id: uuid.UUID, client_timestamp: datetime,
    device_id: uuid.UUID,
) -> bool:
    """True when someone else changed the row after the op was queued (a genuine conflict)."""
    foreign_at = await last_foreign_change_at(session, table, row_id, device_id)
    return foreign_at is not None and truncate_to_ms(foreign_at) > truncate_to_ms(client_timestamp)
