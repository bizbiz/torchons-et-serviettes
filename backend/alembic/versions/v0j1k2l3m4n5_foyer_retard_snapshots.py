"""Table foyer_retard_snapshots — historique journalier du retard du foyer.

Revision ID: v0j1k2l3m4n5
Revises: u9i0j1k2l3m4
Create Date: 2026-04-27

Snapshot quotidien (1 ligne par foyer par jour) du retard agrege calcule a
03h00 UTC par le job APScheduler. Reconstruit en backfill via les
recurrences + l'historique des completions, donc une fois la table cree,
elle se peuple lazily a la 1ere lecture de /api/stats/retard-timeline.

Cles primaires : (foyer_id, date) — 1 row max par jour. Idempotent : un
re-run du backfill upsert sans doublonner.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'v0j1k2l3m4n5'
down_revision: Union[str, None] = 'u9i0j1k2l3m4'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        'foyer_retard_snapshots',
        sa.Column(
            'foyer_id', sa.dialects.postgresql.UUID(as_uuid=True),
            sa.ForeignKey('foyers.id', ondelete='CASCADE'),
            nullable=False,
        ),
        sa.Column('date', sa.Date(), nullable=False),
        sa.Column('total_minutes', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('task_count', sa.Integer(), nullable=False, server_default='0'),
        sa.Column(
            'computed_at', sa.DateTime(timezone=True),
            server_default=sa.text('now()'), nullable=False,
        ),
        sa.PrimaryKeyConstraint('foyer_id', 'date'),
    )
    # Index pour les SELECT chronologiques sur un foyer.
    op.create_index(
        'ix_foyer_retard_snapshots_foyer_date',
        'foyer_retard_snapshots',
        ['foyer_id', 'date'],
    )


def downgrade() -> None:
    op.drop_index('ix_foyer_retard_snapshots_foyer_date', table_name='foyer_retard_snapshots')
    op.drop_table('foyer_retard_snapshots')
