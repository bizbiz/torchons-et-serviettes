"""Default delay before a rating is asked again: 6 months becomes 24.

`user_settings.affinity_expiry_months` was 6 by default, and the app never sent it (it
hard-coded 6), so the slider had no effect: a stored 6 is the old default, not a choice.
Existing rows at 6 move to 24, and the column default follows. The slider now goes
from 1 to 24 months.

Revision ID: c2d3e4f5a6b7
Revises: b1c2d3e4f5a6
"""

from typing import Sequence, Union

from alembic import op

revision: str = "c2d3e4f5a6b7"
down_revision: Union[str, None] = "b1c2d3e4f5a6"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.alter_column("user_settings", "affinity_expiry_months", server_default="24")
    op.execute(
        "UPDATE user_settings SET affinity_expiry_months = 24 "
        "WHERE affinity_expiry_months = 6"
    )


def downgrade() -> None:
    # Only the column default goes back to 6. The values are left alone: after the
    # upgrade, a 24 cannot be told apart from a 24 the member chose on the slider.
    op.alter_column("user_settings", "affinity_expiry_months", server_default="6")
