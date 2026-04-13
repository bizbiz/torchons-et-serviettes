"""Ajout table task_frequency_votes + index historique change_log.

Revision ID: c1d2e3f4a5b6
Revises: 095468d44a09
Create Date: 2026-04-13
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'c1d2e3f4a5b6'
down_revision: Union[str, None] = '095468d44a09'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # Table des votes de frequence (every_n_days) par utilisateur.
    # Le consensus = moyenne arrondie, stockee dans tasks.recurrence_rule.
    op.create_table(
        'task_frequency_votes',
        sa.Column('task_id', sa.UUID(), nullable=False),
        sa.Column('user_id', sa.UUID(), nullable=False),
        sa.Column('every_n_days', sa.Integer(), nullable=False),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
        sa.ForeignKeyConstraint(['task_id'], ['tasks.id'], ondelete='CASCADE'),
        sa.ForeignKeyConstraint(['user_id'], ['users.id'], ondelete='CASCADE'),
        sa.PrimaryKeyConstraint('task_id', 'user_id'),
    )

    # Index pour le GET /tasks/{id}/history — filtre change_log par (table, row_id)
    # ordonne par changed_at DESC. Absent des migrations precedentes.
    op.create_index(
        'ix_change_log_table_row_changed_at',
        'change_log',
        ['table_name', 'row_id', sa.text('changed_at DESC')],
    )

    # Backfill : chaque utilisateur assigne recoit un vote initial egal a
    # l'every_n_days actuel de la tache. Evite qu'un premier vote ecrase
    # brutalement le consensus. Ne seed que les taches recurrentes de type
    # interval (les autres types — weekdays, monthly, custom — ne sont pas
    # impactes par le vote fréquence pour le moment).
    op.execute(
        """
        INSERT INTO task_frequency_votes (task_id, user_id, every_n_days, created_at, updated_at)
        SELECT t.id, ta.user_id, (t.recurrence_rule->>'every_n_days')::int, now(), now()
        FROM tasks t
        JOIN task_assignments ta ON ta.task_id = t.id
        WHERE t.deleted_at IS NULL
          AND t.recurrence_rule IS NOT NULL
          AND t.recurrence_rule->>'type' = 'interval'
          AND (t.recurrence_rule->>'every_n_days') ~ '^[0-9]+$'
        """
    )


def downgrade() -> None:
    op.drop_index('ix_change_log_table_row_changed_at', table_name='change_log')
    op.drop_table('task_frequency_votes')
