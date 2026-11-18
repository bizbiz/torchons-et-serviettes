"""/sync handler of the `task_delegations` table: « À faire aujourd'hui ».

- INSERT `{"task_id", "recipient_ids"}`: hand the task (row_id = the delegation id, generated
  by the app). Acknowledged without writing when the household switched the feature off, or
  when the task already has an active delegation (the first one wins).
- DELETE (no data): cancel it. Only its author's DELETE writes; anyone else's is acknowledged
  and changes nothing.

The op's data is replaced by the full row before sync.py journals it, so that another device
pulling the change_log can cache the delegation as it is (author, recipients, date), and so
that `_visible_changes` can hide it with its task. Rules: services/delegations.py.
"""

from __future__ import annotations

import uuid

from sqlalchemy.ext.asyncio import AsyncSession

from app.api.sync_errors import OutOfScopeError
from app.schemas.sync import SyncOperation
from app.services.delegations import cancel_delegation, create_delegation
from app.utils.errors import LocalizedHTTPException


async def handle_delegation(
    session: AsyncSession, op: SyncOperation, user_id: str, foyer_id: str,
) -> uuid.UUID | None:
    data = op.data or {}
    if op.operation == "INSERT":
        try:
            written = await create_delegation(
                session, op.row_id, data.get("task_id"), user_id, foyer_id, data.get("recipient_ids"),
            )
        except LookupError as e:
            raise OutOfScopeError(str(e)) from e
    elif op.operation == "DELETE":
        written = await cancel_delegation(session, op.row_id, user_id, foyer_id)
    else:
        raise LocalizedHTTPException(
            status_code=400,
            error_code="SYNC_UNSUPPORTED_OPERATION_FORMAT",
            format_args={"scope": "delegations", "operation": op.operation},
        )
    if written is None:
        return None
    op.data = written
    return op.row_id
