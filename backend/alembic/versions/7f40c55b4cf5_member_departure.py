"""tasks.suspended_at and users.anonymized_at: what a member's departure leaves behind.

tasks.suspended_at: a solo task deactivated because its only assignee left the household. Set
by app/services/task_departure.py when a member leaves, cleared when they come back. It tells
those tasks apart from the ones someone deactivated by hand, which a return must not revive.
NULL everywhere at creation: no production task is assigned to a former member (read-only
measurement on 2026-11-02, 0 rows), so there is nothing to backfill.

users.anonymized_at: the account was anonymized by the nightly cleanup, 30 days after it left
its last household or was deleted (app/services/member_departure.py). NULL everywhere at
creation: no account had been purged in production before 2026-11-02.

Revision ID: 7f40c55b4cf5
Revises: f9a0b1c2d3e4
"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "7f40c55b4cf5"
down_revision: Union[str, None] = "f9a0b1c2d3e4"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column("tasks", sa.Column("suspended_at", sa.DateTime(timezone=True), nullable=True))
    op.add_column("users", sa.Column("anonymized_at", sa.DateTime(timezone=True), nullable=True))


def downgrade() -> None:
    op.drop_column("users", "anonymized_at")
    op.drop_column("tasks", "suspended_at")
