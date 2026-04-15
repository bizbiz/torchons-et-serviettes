"""Ajoute collective_id sur task_completions pour les completions 'fait ensemble'.

Revision ID: n2c3d4e5f6a7
Revises: m1b2c3d4e5f6
Create Date: 2026-04-15

Une completion collective est stockee comme N lignes task_completions, une
par participant, groupees par un meme collective_id. Le champ est nullable :
les completions solo restent a NULL. Index simple pour le lookup 'toutes les
lignes d'une meme completion ensemble'.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision: str = 'n2c3d4e5f6a7'
down_revision: Union[str, None] = 'm1b2c3d4e5f6'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        'task_completions',
        sa.Column('collective_id', postgresql.UUID(as_uuid=True), nullable=True),
    )
    op.create_index(
        'ix_task_completions_collective_id',
        'task_completions',
        ['collective_id'],
    )


def downgrade() -> None:
    op.drop_index('ix_task_completions_collective_id', table_name='task_completions')
    op.drop_column('task_completions', 'collective_id')
