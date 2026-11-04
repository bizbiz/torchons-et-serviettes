"""fair_share_history: who changed the household's target shares, when, before -> after.

One row per accepted PUT /api/fair-share/config. `before` / `after` are JSONB maps
str(user_id) -> ratio. No data to migrate: the table starts empty.

Revision ID: d1f6a8b3c5e7
Revises: c9d5e3f7a2b4
"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "d1f6a8b3c5e7"
down_revision: Union[str, None] = "c9d5e3f7a2b4"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "fair_share_history",
        sa.Column(
            "id", postgresql.UUID(as_uuid=True), primary_key=True,
            server_default=sa.text("uuid_generate_v4()"),
        ),
        sa.Column(
            "foyer_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("foyers.id", ondelete="CASCADE"), nullable=False,
        ),
        sa.Column(
            "changed_by", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id"), nullable=False,
        ),
        sa.Column(
            "changed_at", sa.DateTime(timezone=True),
            server_default=sa.text("now()"), nullable=False,
        ),
        sa.Column("before", postgresql.JSONB(), nullable=False),
        sa.Column("after", postgresql.JSONB(), nullable=False),
    )
    op.create_index(
        "ix_fair_share_history_foyer_changed_at",
        "fair_share_history",
        ["foyer_id", "changed_at"],
    )


def downgrade() -> None:
    op.drop_index("ix_fair_share_history_foyer_changed_at", table_name="fair_share_history")
    op.drop_table("fair_share_history")
