"""notification_tokens: lier un jeton FCM a un appareil

Ecrite a la main, comme la precedente : `--autogenerate` embarque ici la derive
de la base de dev (index sur objectives, contexts, presence_days, tasks...) et
toucherait des tables etrangeres a ce changement.

Sans cette colonne, revoquer un appareil coupait son API mais lui laissait les
notifications push du foyer — titres de taches, qui a fait quoi.

Revision ID: 8a9b0c1d2e3f
Revises: 7f814b81fa83
Create Date: 2026-09-03
"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "8a9b0c1d2e3f"
down_revision: Union[str, None] = "7f814b81fa83"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # Nullable : les lignes existantes n'ont pas d'appareil connu, et un jeton
    # legacy sans claim `device_id` n'en produira jamais. Elles se rattacheront
    # d'elles-memes au prochain enregistrement FCM, qui lit le JWT.
    op.add_column(
        "notification_tokens",
        sa.Column("device_id", sa.String(length=64), nullable=True),
    )
    op.create_index(
        "ix_notification_tokens_device_id", "notification_tokens", ["device_id"]
    )


def downgrade() -> None:
    op.drop_index("ix_notification_tokens_device_id", table_name="notification_tokens")
    op.drop_column("notification_tokens", "device_id")
