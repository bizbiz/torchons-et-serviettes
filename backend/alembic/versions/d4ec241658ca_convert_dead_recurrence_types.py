"""tasks: convert the recurrence types stored before they died

`weekdays`, `monthly` and `custom` died in v0.27. Since then
`normalize_recurrence_rule` converts them at WRITE time, but rows written
before that were never rewritten, and no read path knows these types: such a
task has no due date and stays green forever. Measured in prod on 2026-09-30:
one live task, « Submit my work hours », `{"type": "monthly", "day_of_month": 1}`,
done once on 29/07 and silent since.

Conversion, decided by the maintainer on 2026-09-30:
- `monthly`  -> `calendar`, every month, same day (capped at 28): the exact
  intent, no drift. An `interval: 30` would drift with each late completion.
- `weekdays` -> `interval`, 7 / number of days; `custom` -> `interval`,
  every_n_weeks * 7. Same values as `_legacy_interval`.

The logic is COPIED here on purpose, not imported: a migration must replay the
same way forever, whatever `app/` becomes.

Each converted row gets `version + 1`, a fresh `updated_at` and a
`change_log` UPDATE entry, the same shape the frequency vote writes
(`{"recurrence_rule": ...}`): phones pull the change through /sync without
waiting for a full refresh.

Deleted tasks are left alone: nothing reads them.

Revision ID: d4ec241658ca
Revises: 8a9b0c1d2e3f
Create Date: 2026-09-30
"""

import json
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "d4ec241658ca"
down_revision: Union[str, None] = "8a9b0c1d2e3f"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

DEAD_TYPES = ("weekdays", "monthly", "custom")


def _int(value, default):
    try:
        return int(round(float(value)))
    except (TypeError, ValueError):
        return default


def _convert(rule: dict) -> dict:
    rtype = rule.get("type")
    if rtype == "monthly":
        day = min(28, max(1, _int(rule.get("day_of_month"), 1)))
        return {"type": "calendar", "months": list(range(1, 13)), "day": day, "grace_days": 0}
    if rtype == "weekdays":
        days = [d for d in (_int(x, None) for x in rule.get("days") or []) if d is not None]
        return {"type": "interval", "every_n_days": max(1, round(7 / len(days))) if days else 7}
    # custom
    weeks = _int(rule.get("every_n_weeks"), 1) or 1
    return {"type": "interval", "every_n_days": max(1, weeks * 7)}


def upgrade() -> None:
    conn = op.get_bind()
    rows = conn.execute(
        sa.text(
            "SELECT id, foyer_id, recurrence_rule FROM tasks "
            "WHERE deleted_at IS NULL AND recurrence_rule ->> 'type' = ANY(:dead)"
        ),
        {"dead": list(DEAD_TYPES)},
    ).fetchall()

    for task_id, foyer_id, rule in rows:
        old = rule if isinstance(rule, dict) else json.loads(rule)
        new = _convert(old)
        conn.execute(
            sa.text(
                "UPDATE tasks SET recurrence_rule = cast(:rule as jsonb), "
                "version = version + 1, updated_at = now() WHERE id = :id"
            ),
            {"rule": json.dumps(new), "id": task_id},
        )
        conn.execute(
            sa.text(
                "INSERT INTO change_log (server_version, table_name, row_id, operation, "
                "changed_by, foyer_id, old_data, new_data, client_op_id) VALUES ("
                "(SELECT COALESCE(MAX(server_version), 0) + 1 FROM change_log), "
                "'tasks', :id, 'UPDATE', NULL, :foyer, "
                "cast(:old as jsonb), cast(:new as jsonb), NULL)"
            ),
            {
                "id": task_id,
                "foyer": foyer_id,
                "old": json.dumps({"recurrence_rule": old}),
                "new": json.dumps({"recurrence_rule": new}),
            },
        )
        print(f"[d4ec241658ca] task {str(task_id)[:8]}: {old} -> {new}")


def downgrade() -> None:
    # Irreversible by design: the dead types have no reader to go back to.
    pass
