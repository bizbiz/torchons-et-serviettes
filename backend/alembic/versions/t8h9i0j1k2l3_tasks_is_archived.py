"""Ajoute is_archived sur tasks + index partiel.

Revision ID: t8h9i0j1k2l3
Revises: s7g8h9i0j1k2
Create Date: 2026-04-24

Champ pour l'auto-archivage soft des taches non-recurrentes completees
depuis plus de ARCHIVE_AFTER_DAYS jours. Un job quotidien (app.main
lifespan) met is_archived=true. Les lectures liste filtrent is_archived=false
par defaut. Archivage lecture seule cote UI (pas de restore — l'utilisateur
recree la tache si besoin).

Index partiel sur is_archived=false : la plupart des lectures liste
n'incluent que les taches actives, on optimise ce cas sans peser sur
la zone archivee qui n'est jamais requetee a chaud.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 't8h9i0j1k2l3'
down_revision: Union[str, None] = 's7g8h9i0j1k2'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        'tasks',
        sa.Column(
            'is_archived',
            sa.Boolean(),
            nullable=False,
            server_default=sa.text('false'),
        ),
    )
    op.create_index(
        'ix_tasks_foyer_active_not_archived',
        'tasks',
        ['foyer_id'],
        postgresql_where=sa.text('is_archived = false AND deleted_at IS NULL'),
    )


def downgrade() -> None:
    op.drop_index('ix_tasks_foyer_active_not_archived', table_name='tasks')
    op.drop_column('tasks', 'is_archived')
