"""module_guide_views: which module guide pages a member has already seen.

Each module has a guide page (why / how), shown once per member: when the module
is proposed, when the member enables it by hand, or when another member enabled a
collective module for the household. "Once" is stored here, per USER and not per
foyer, so it holds across devices and reinstalls.

Backfill: every module is already on in the production foyers, and nobody should
get a pop-up after the update for a feature they have been using for weeks. So
the guides are marked as seen for every active member (`user_foyers.left_at IS
NULL`) times every module enabled for them: a collective row (`user_id IS NULL`)
of their foyer, or a personal row of their own. The info button in Settings stays
available either way. `ON CONFLICT DO NOTHING` because a member of two foyers
meets the same module twice. Rows are written with the registry keys as they
stand in `module_states`; the migration does not read modules.yaml.

Revision ID: b8c4d2e6f1a3
Revises: 7f40c55b4cf5
"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "b8c4d2e6f1a3"
down_revision: Union[str, None] = "7f40c55b4cf5"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


# Module-level so the test can run the very statement the migration runs.
BACKFILL_SQL = """
            INSERT INTO module_guide_views (user_id, module_key, seen_at)
            SELECT uf.user_id, ms.module_key, now()
            FROM user_foyers uf
            JOIN module_states ms
              ON ms.foyer_id = uf.foyer_id
             AND ms.enabled
             AND (ms.user_id IS NULL OR ms.user_id = uf.user_id)
            WHERE uf.left_at IS NULL
            ON CONFLICT DO NOTHING
            """


def upgrade() -> None:
    op.create_table(
        "module_guide_views",
        sa.Column(
            "user_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("module_key", sa.String(length=50), nullable=False),
        sa.Column(
            "seen_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.PrimaryKeyConstraint("user_id", "module_key"),
    )

    op.execute(sa.text(BACKFILL_SQL))


def downgrade() -> None:
    op.drop_table("module_guide_views")
