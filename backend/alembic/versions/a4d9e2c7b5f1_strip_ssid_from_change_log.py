"""Wi-Fi names (SSID) out of change_log, and out of the memberships of former members.

Revision ID: a4d9e2c7b5f1
Revises: f3b8d5c2e9a1
Create Date: 2026-11-16

Privacy decision 3 (2026-11-15): a member's Wi-Fi names are kept only while they are a member.
Until now PUT /api/settings copied the whole request body into change_log.new_data, home_ssids
included, and a member who left kept theirs in user_foyers. The code no longer does either
(api/settings.py, services/lifecycle.py); this cleans what it left behind.

- change_log: the `home_ssids` key is removed from new_data and old_data. The rows stay, every
  other key stays. A row whose only key was home_ssids keeps an empty object.
- user_foyers: home_ssids emptied where the member has left (left_at set), as a departure
  now does.

Idempotent: the WHERE clauses only match what still carries an SSID, a second run changes
nothing. Downgrade is a no-op: the removed values are not kept anywhere, on purpose.
"""
from typing import Sequence, Union

from alembic import op


revision: str = 'a4d9e2c7b5f1'
down_revision: Union[str, None] = 'f3b8d5c2e9a1'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

# Module-level so that tests/test_account_erasure.py runs the very statements shipped here.
STATEMENTS = (
    """
    UPDATE change_log SET new_data = new_data - 'home_ssids'
    WHERE jsonb_typeof(new_data) = 'object' AND new_data ? 'home_ssids'
    """,
    """
    UPDATE change_log SET old_data = old_data - 'home_ssids'
    WHERE jsonb_typeof(old_data) = 'object' AND old_data ? 'home_ssids'
    """,
    """
    UPDATE user_foyers SET home_ssids = ''
    WHERE left_at IS NOT NULL AND home_ssids <> ''
    """,
)


def upgrade() -> None:
    for statement in STATEMENTS:
        op.execute(statement)


def downgrade() -> None:
    # No-op: the SSIDs removed by upgrade() were personal data with no use left; restoring
    # them would need a copy this migration deliberately does not make.
    pass
