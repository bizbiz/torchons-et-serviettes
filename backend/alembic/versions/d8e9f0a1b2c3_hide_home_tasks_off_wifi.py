"""Masquer taches maison hors WiFi — toggle user-level (P1 sweep).

Revision ID: d8e9f0a1b2c3
Revises: c7d8e9f0a1b2
Create Date: 2026-05-17

Ajoute `user_settings.hide_home_tasks_off_wifi` (BOOLEAN, default TRUE).

Avant : le masquage des taches de categorie requires_home quand le device
n'est pas sur un WiFi maison etait implicite et toujours actif. On le rend
configurable per-user. Toutes les lignes existantes sont a TRUE pour
preserver le comportement historique.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'd8e9f0a1b2c3'
down_revision: Union[str, None] = 'c7d8e9f0a1b2'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        'user_settings',
        sa.Column(
            'hide_home_tasks_off_wifi',
            sa.Boolean(),
            nullable=False,
            server_default=sa.true(),
        ),
    )


def downgrade() -> None:
    op.drop_column('user_settings', 'hide_home_tasks_off_wifi')
