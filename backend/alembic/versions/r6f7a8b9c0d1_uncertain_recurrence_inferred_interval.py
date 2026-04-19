"""Ajout inferred_interval_days sur tasks pour la recurrence uncertain.

Revision ID: r6f7a8b9c0d1
Revises: q5e6f7a8b9c0
Create Date: 2026-04-19

Prepare l'arrivee du 6e type de recurrence "uncertain" (recurrence sans
cadence definie, fréquence inférée depuis l'historique). La colonne
inferred_interval_days est mise à jour à chaque completion et utilisée
par /api/suggestions et /api/maintenance-rate. NULL tant qu'on n'a pas
assez d'historique (cold start).
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'r6f7a8b9c0d1'
down_revision: Union[str, None] = 'q5e6f7a8b9c0'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        'tasks',
        sa.Column('inferred_interval_days', sa.Float(), nullable=True),
    )


def downgrade() -> None:
    op.drop_column('tasks', 'inferred_interval_days')
