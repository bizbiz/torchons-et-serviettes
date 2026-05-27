"""Drop colonne freshness_threshold de user_settings (v0.10.0).

Revision ID: m1b2c3d4e5f6
Revises: l0a1b2c3d4e5
Create Date: 2026-04-15

Refonte du modele freshness vers un modele deterministe base sur due_date.
Le knob freshness_threshold (qui filtrait les taches "a faire" cote frontend
et l'endpoint /api/suggestions) n'a plus de sens : le modele est maintenant
base sur l'etat (GREEN/ORANGE/RED) calcule depuis due_date, sans seuil
configurable. Voir CODING_CONVENTIONS.md section "Urgence".
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'm1b2c3d4e5f6'
down_revision: Union[str, None] = 'l0a1b2c3d4e5'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.drop_column('user_settings', 'freshness_threshold')


def downgrade() -> None:
    op.add_column(
        'user_settings',
        sa.Column(
            'freshness_threshold',
            sa.Integer(),
            nullable=False,
            server_default='10',
        ),
    )
