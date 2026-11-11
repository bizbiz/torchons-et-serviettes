"""user_settings.notif_overdue_hour: the hour of the « tasks that turned overdue » notification.

Default 8, not null: existing rows take 8 through the server default.

Revision ID: e2a7c4b9d1f3
Revises: d1f6a8b3c5e7
"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "e2a7c4b9d1f3"
down_revision: Union[str, None] = "d1f6a8b3c5e7"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        "user_settings",
        sa.Column("notif_overdue_hour", sa.Integer(), nullable=False, server_default="8"),
    )


def downgrade() -> None:
    op.drop_column("user_settings", "notif_overdue_hour")
