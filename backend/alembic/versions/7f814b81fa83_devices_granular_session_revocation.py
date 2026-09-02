"""devices: granular session revocation

Hand-written on purpose. `alembic revision --autogenerate` produced 34
operations here: the dev database has drifted from the models (indexes on
objectives, contexts, presence_days, processed_ops, tasks, user_prompts),
and shipping that drift alongside a new table would have altered tables
this change has nothing to do with. Only the `devices` table is kept.

Revision ID: 7f814b81fa83
Revises: e5f6a1b2c3d4
Create Date: 2026-09-02
"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "7f814b81fa83"
down_revision: Union[str, None] = "e5f6a1b2c3d4"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "devices",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("device_id", sa.String(length=64), nullable=False),
        sa.Column("device_name", sa.String(length=200), nullable=True),
        sa.Column(
            "platform", sa.String(length=20), server_default="android", nullable=False
        ),
        sa.Column(
            "first_seen",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "last_seen",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("user_id", "device_id", name="uq_devices_user_device"),
    )
    op.create_index("ix_devices_device_id", "devices", ["device_id"])


def downgrade() -> None:
    op.drop_index("ix_devices_device_id", table_name="devices")
    op.drop_table("devices")
