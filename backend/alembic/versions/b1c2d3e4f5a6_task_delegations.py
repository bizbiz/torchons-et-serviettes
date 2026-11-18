"""« À faire aujourd'hui »: task_delegations + recipients, and two user_settings columns.

A delegation is active while `cancelled_at` and `settled_at` are both NULL; one active
delegation per task at most (partial unique index). `announced_at` per recipient is the
grouped notification's state, kept in the database so that a restart loses nothing.
`notif_today_reminders` is the personal switch of the 12/16/20 h reminders (on by default);
`today_notified_at` is server-owned, the time of the member's last such notification.

Spec: docs/plans/a-faire-aujourdhui.md.

Revision ID: b1c2d3e4f5a6
Revises: a4d9e2c7b5f1
"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "b1c2d3e4f5a6"
down_revision: Union[str, None] = "a4d9e2c7b5f1"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "task_delegations",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "foyer_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("foyers.id", ondelete="CASCADE"), nullable=False,
        ),
        sa.Column(
            "task_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("tasks.id", ondelete="CASCADE"), nullable=False,
        ),
        sa.Column(
            "author_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False,
        ),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.Column("cancelled_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("settled_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.create_index(
        "uq_task_delegations_active_task", "task_delegations", ["task_id"], unique=True,
        postgresql_where=sa.text("cancelled_at IS NULL AND settled_at IS NULL"),
    )
    op.create_index("ix_task_delegations_foyer", "task_delegations", ["foyer_id"])
    op.create_table(
        "task_delegation_recipients",
        sa.Column(
            "delegation_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("task_delegations.id", ondelete="CASCADE"), primary_key=True,
        ),
        sa.Column(
            "user_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"), primary_key=True,
        ),
        sa.Column("announced_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.add_column(
        "user_settings",
        sa.Column("notif_today_reminders", sa.Boolean(), server_default="true", nullable=False),
    )
    op.add_column(
        "user_settings",
        sa.Column("today_notified_at", sa.DateTime(timezone=True), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("user_settings", "today_notified_at")
    op.drop_column("user_settings", "notif_today_reminders")
    op.drop_table("task_delegation_recipients")
    op.drop_index("ix_task_delegations_foyer", table_name="task_delegations")
    op.drop_index("uq_task_delegations_active_task", table_name="task_delegations")
    op.drop_table("task_delegations")
