"""Ajout fair_share_tolerance_percent dans user_settings.

Revision ID: b8c9d0e1f2a3
Revises: a7b8c9d0e1f2
Create Date: 2026-04-11
"""
from typing import Sequence, Union
from alembic import op
import sqlalchemy as sa

revision: str = 'b8c9d0e1f2a3'
down_revision: Union[str, None] = 'a7b8c9d0e1f2'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # Tolerance perso pour le message fair-share : seuil au-dela duquel
    # l'app affiche "tu as fait plus que ta part". Defaut 30%.
    op.add_column(
        'user_settings',
        sa.Column(
            'fair_share_tolerance_percent',
            sa.Integer(),
            nullable=False,
            server_default='30',
        ),
    )


def downgrade() -> None:
    op.drop_column('user_settings', 'fair_share_tolerance_percent')
