"""user_settings: per-member cadence of the automatic rating pop-up.

`rating_prompt_gap_days` is the number of days between two automatic pop-ups (starts at
1, moves between 1 and 10 with the member's answers). `rating_prompt_next_at` is the
earliest moment of the next one; NULL means "never scheduled, due now".

Revision ID: c9d5e3f7a2b4
Revises: b8c4d2e6f1a3
"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "c9d5e3f7a2b4"
down_revision: Union[str, None] = "b8c4d2e6f1a3"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        "user_settings",
        sa.Column(
            "rating_prompt_gap_days", sa.Integer(), server_default="1", nullable=False
        ),
    )
    op.add_column(
        "user_settings",
        sa.Column("rating_prompt_next_at", sa.DateTime(timezone=True), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("user_settings", "rating_prompt_next_at")
    op.drop_column("user_settings", "rating_prompt_gap_days")
