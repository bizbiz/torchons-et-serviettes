"""users.recovery_lookup: deterministic key of the recovery code.

Restore found the account by one argon2 verify per account holding a code (61 ms
each, 16 accounts in production on 2026-10-12). The key (HMAC of the code, see
app/utils/recovery_code.lookup_key) finds it with one verify. Existing codes are
only stored hashed: their key is written on the next regeneration or the first
successful restore, so the column starts NULL everywhere.

Revision ID: e7f8a9b0c1d2
Revises: d4ec241658ca
"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "e7f8a9b0c1d2"
down_revision: Union[str, None] = "d4ec241658ca"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column("users", sa.Column("recovery_lookup", sa.String(length=64), nullable=True))
    op.create_index("ix_users_recovery_lookup", "users", ["recovery_lookup"])


def downgrade() -> None:
    op.drop_index("ix_users_recovery_lookup", table_name="users")
    op.drop_column("users", "recovery_lookup")
