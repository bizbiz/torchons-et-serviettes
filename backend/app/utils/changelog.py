"""Helper pour enregistrer les changements dans change_log (sync offline)."""

from __future__ import annotations

import uuid

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession


async def record_change(
    session: AsyncSession,
    table_name: str,
    row_id: uuid.UUID,
    operation: str,
    changed_by: str | None = None,
    old_data: dict | None = None,
    new_data: dict | None = None,
    client_op_id: uuid.UUID | None = None,
) -> None:
    """Insere une entree dans change_log. Appele apres chaque mutation."""
    import json
    await session.execute(
        text("""
            INSERT INTO change_log (server_version, table_name, row_id, operation, changed_by, old_data, new_data, client_op_id)
            VALUES (
                (SELECT COALESCE(MAX(server_version), 0) + 1 FROM change_log),
                :table_name, :row_id, :operation, :changed_by,
                cast(:old_data as jsonb), cast(:new_data as jsonb), :client_op_id)
        """),
        {
            "table_name": table_name,
            "row_id": row_id,
            "operation": operation,
            "changed_by": uuid.UUID(changed_by) if changed_by else None,
            "old_data": json.dumps(old_data, default=str) if old_data else None,
            "new_data": json.dumps(new_data, default=str) if new_data else None,
            "client_op_id": client_op_id,
        },
    )
