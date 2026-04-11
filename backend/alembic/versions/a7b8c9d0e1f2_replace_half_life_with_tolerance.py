"""Remplace freshness_half_life_hours par enum tolerance.

Revision ID: a7b8c9d0e1f2
Revises: f6a7b8c9d0e1
Create Date: 2026-04-11
"""
from typing import Sequence, Union
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = 'a7b8c9d0e1f2'
down_revision: Union[str, None] = 'f6a7b8c9d0e1'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # Drop ancien champ override numerique (les valeurs en base sont
    # toutes redondantes avec NORMAL = nominal interval, donc pas de perte).
    op.drop_column('tasks', 'freshness_half_life_hours')

    # Cree le type ENUM Postgres natif task_tolerance.
    task_tolerance = postgresql.ENUM(
        'STRICT', 'NORMAL', 'SOUPLE', name='task_tolerance'
    )
    task_tolerance.create(op.get_bind())

    # Ajoute la colonne avec NORMAL par defaut pour toutes les taches existantes.
    op.add_column(
        'tasks',
        sa.Column(
            'tolerance',
            sa.Enum('STRICT', 'NORMAL', 'SOUPLE', name='task_tolerance', create_type=False),
            nullable=False,
            server_default='NORMAL',
        ),
    )


def downgrade() -> None:
    op.drop_column('tasks', 'tolerance')
    op.execute('DROP TYPE task_tolerance')
    op.add_column(
        'tasks',
        sa.Column('freshness_half_life_hours', sa.Float(), nullable=True),
    )
