"""task_frequency_votes : every_n_days -> days_per_month (12 mois).

Revision ID: w1k2l3m4n5o6
Revises: v0j1k2l3m4n5
Create Date: 2026-04-28

Refonte vote-first (v0.27) : un vote de frequence n'est plus un seul int
mais un dict 12-mois `{1: int, 2: int, ..., 12: int}`. Permet de calculer
une moyenne par mois avec des votes mixtes interval / monthly_variable.

Migration des donnees existantes : every_n_days = N devient
days_per_month = {1..12: N} (vote interval = meme valeur sur 12 mois).
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import JSONB


revision: str = 'w1k2l3m4n5o6'
down_revision: Union[str, None] = 'v0j1k2l3m4n5'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # 1. Ajout colonne days_per_month, nullable temporairement.
    op.add_column(
        'task_frequency_votes',
        sa.Column('days_per_month', JSONB(), nullable=True),
    )
    # 2. Backfill : every_n_days=N -> {"1":N, "2":N, ..., "12":N}.
    # Construit en SQL pour eviter de charger toute la table en memoire.
    op.execute("""
        UPDATE task_frequency_votes
        SET days_per_month = jsonb_build_object(
            '1',  every_n_days, '2',  every_n_days, '3',  every_n_days,
            '4',  every_n_days, '5',  every_n_days, '6',  every_n_days,
            '7',  every_n_days, '8',  every_n_days, '9',  every_n_days,
            '10', every_n_days, '11', every_n_days, '12', every_n_days
        )
        WHERE days_per_month IS NULL
    """)
    # 3. NOT NULL + drop ancienne colonne.
    op.alter_column('task_frequency_votes', 'days_per_month', nullable=False)
    op.drop_column('task_frequency_votes', 'every_n_days')


def downgrade() -> None:
    # Recree every_n_days, valeur derivee = moyenne arrondie des 12 mois.
    op.add_column(
        'task_frequency_votes',
        sa.Column('every_n_days', sa.Integer(), nullable=True),
    )
    op.execute("""
        UPDATE task_frequency_votes
        SET every_n_days = ROUND((
            (days_per_month->>'1')::int + (days_per_month->>'2')::int +
            (days_per_month->>'3')::int + (days_per_month->>'4')::int +
            (days_per_month->>'5')::int + (days_per_month->>'6')::int +
            (days_per_month->>'7')::int + (days_per_month->>'8')::int +
            (days_per_month->>'9')::int + (days_per_month->>'10')::int +
            (days_per_month->>'11')::int + (days_per_month->>'12')::int
        ) / 12.0)
    """)
    op.alter_column('task_frequency_votes', 'every_n_days', nullable=False)
    op.drop_column('task_frequency_votes', 'days_per_month')
