"""Helper pour enregistrer les changements dans change_log (sync offline)."""

from __future__ import annotations

import uuid

from sqlalchemy import text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

#: `change_log.server_version` is UNIQUE; this is the name of its constraint.
VERSION_CONSTRAINT = "change_log_server_version_key"
#: Attempts at a version number before the collision is re-raised.
MAX_VERSION_ATTEMPTS = 5


_INSERT_CHANGE = text("""
    INSERT INTO change_log (
        server_version, table_name, row_id, operation, changed_by,
        foyer_id, old_data, new_data, client_op_id)
    VALUES (
        (SELECT COALESCE(MAX(server_version), 0) + 1 FROM change_log),
        :table_name, :row_id, :operation, :changed_by, :foyer_id,
        cast(:old_data as jsonb), cast(:new_data as jsonb), :client_op_id)
""")


def _is_version_collision(exc: IntegrityError) -> bool:
    """True when `exc` is a duplicate `server_version` and nothing else.

    The constraint is read from the driver error (asyncpg: `constraint_name`), then from its
    message, which names it. Any other IntegrityError is a real bug and must not be retried.
    """
    orig = exc.orig
    for err in (orig, getattr(orig, "__cause__", None)):
        if getattr(err, "constraint_name", None) == VERSION_CONSTRAINT:
            return True
    return VERSION_CONSTRAINT in str(orig)


async def record_change(
    session: AsyncSession,
    table_name: str,
    row_id: uuid.UUID,
    operation: str,
    changed_by: str | None = None,
    foyer_id: str | uuid.UUID | None = None,
    old_data: dict | None = None,
    new_data: dict | None = None,
    client_op_id: uuid.UUID | None = None,
) -> None:
    """Insere une entree dans change_log. Appele apres chaque mutation.

    foyer_id est obligatoire depuis v0.7.0 (multi-foyer). Les callers doivent
    le passer depuis le contexte current_user["foyer_id"].

    The row is numbered `MAX(server_version) + 1`. Two transactions that read the same
    maximum compute the same number: the second one BLOCKS on the unique index until the
    first commits (or rolls back), then fails, and retries here with the next number. Versions
    therefore follow commit order, which `api/sync.py::_pull_changes` relies on (its cursor
    assumes no lower version commits after a higher one has been delivered, or a pull would
    skip a change). A global lock taken here would give the same order but would serialise
    every transaction that writes the change log until it ends; the retry costs nothing
    when there is no collision. Measured 2026-11-20: without it, two members voting
    together got a 500 in 9 rounds out of 10.

    Only the INSERT runs inside the SAVEPOINT, so a retry undoes nothing else the caller did.
    `begin_nested()` first flushes what the caller changed, which stays outside it.
    """
    import json
    if foyer_id is None:
        raise ValueError("record_change : foyer_id est obligatoire (multi-foyer v0.7.0)")
    foyer_uuid = uuid.UUID(foyer_id) if isinstance(foyer_id, str) else foyer_id
    params = {
        "table_name": table_name,
        "row_id": row_id,
        "operation": operation,
        "changed_by": uuid.UUID(changed_by) if changed_by else None,
        "foyer_id": foyer_uuid,
        "old_data": json.dumps(old_data, default=str) if old_data else None,
        "new_data": json.dumps(new_data, default=str) if new_data else None,
        "client_op_id": client_op_id,
    }
    for attempt in range(1, MAX_VERSION_ATTEMPTS + 1):
        try:
            async with session.begin_nested():
                await session.execute(_INSERT_CHANGE, params)
            return
        except IntegrityError as exc:
            if not _is_version_collision(exc) or attempt == MAX_VERSION_ATTEMPTS:
                raise
