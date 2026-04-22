"""Table suggestion_dismissals : log analytics des dismiss.

Revision ID: s7g8h9i0j1k2
Revises: r6f7a8b9c0d1
Create Date: 2026-04-22

Append-only : chaque ✕ cliqué sur une carte de suggestion Home est
inséré. Pas d'impact sur l'algo — uniquement pour stats a posteriori.
Index composite (user_id, foyer_id, dismissed_at) pour les requêtes
analytics futures.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 's7g8h9i0j1k2'
down_revision: Union[str, None] = 'r6f7a8b9c0d1'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        'suggestion_dismissals',
        sa.Column('id', sa.dialects.postgresql.UUID(as_uuid=True),
                  primary_key=True, server_default=sa.text('uuid_generate_v4()')),
        sa.Column('user_id', sa.dialects.postgresql.UUID(as_uuid=True),
                  sa.ForeignKey('users.id', ondelete='CASCADE'), nullable=False),
        sa.Column('foyer_id', sa.dialects.postgresql.UUID(as_uuid=True),
                  sa.ForeignKey('foyers.id', ondelete='CASCADE'), nullable=False),
        sa.Column('task_id', sa.dialects.postgresql.UUID(as_uuid=True),
                  sa.ForeignKey('tasks.id', ondelete='CASCADE'), nullable=False),
        sa.Column('tier', sa.String(16), nullable=False),
        sa.Column('dismissed_at', sa.DateTime(timezone=True),
                  server_default=sa.func.now(), nullable=False),
    )
    op.create_index(
        'ix_suggestion_dismissals_user_foyer_dismissed',
        'suggestion_dismissals',
        ['user_id', 'foyer_id', 'dismissed_at'],
    )


def downgrade() -> None:
    op.drop_index('ix_suggestion_dismissals_user_foyer_dismissed',
                  table_name='suggestion_dismissals')
    op.drop_table('suggestion_dismissals')
