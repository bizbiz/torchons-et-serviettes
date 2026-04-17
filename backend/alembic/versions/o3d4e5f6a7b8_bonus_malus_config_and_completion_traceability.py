"""Systeme bonus/malus points : config par foyer + tracabilite historique.

Revision ID: o3d4e5f6a7b8
Revises: n2c3d4e5f6a7
Create Date: 2026-04-16

Deux changements :

1. Ajout colonnes `multiplier` (float) et `reason` (enum) sur task_completions
   pour tracer pourquoi une completion a rapporte X points. Immuable : on ne
   recalcule jamais le passe si les seuils changent.

2. Nouvelle table `foyer_bonus_config` avec tous les parametres reglables
   par foyer (seuils bonus/malus, plancher, parametres d'analyse
   'systematiquement en retard'). Une ligne par foyer, defauts remplis
   pour les foyers existants.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision: str = 'o3d4e5f6a7b8'
down_revision: Union[str, None] = 'n2c3d4e5f6a7'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


COMPLETION_REASON_VALUES = (
    'NEUTRAL',
    'BONUS_RETARD',
    'MALUS_AVANCE',
    'FIRST_COMPLETION',
    'PERSO',
)


def upgrade() -> None:
    # --- 1. Colonnes tracabilite sur task_completions ---
    reason_enum = postgresql.ENUM(
        *COMPLETION_REASON_VALUES,
        name='completion_reason',
        create_type=True,
    )
    reason_enum.create(op.get_bind(), checkfirst=True)

    op.add_column(
        'task_completions',
        sa.Column(
            'multiplier',
            sa.Float(),
            nullable=False,
            server_default='1.0',
        ),
    )
    op.add_column(
        'task_completions',
        sa.Column(
            'reason',
            sa.Enum(*COMPLETION_REASON_VALUES, name='completion_reason', create_type=False),
            nullable=False,
            server_default='NEUTRAL',
        ),
    )

    # --- 2. Table foyer_bonus_config ---
    op.create_table(
        'foyer_bonus_config',
        sa.Column('foyer_id', postgresql.UUID(as_uuid=True), nullable=False),
        # Seuils malus (% du cycle en avance pour entrer en zone malus)
        sa.Column('seuil_malus_strict', sa.Float(), nullable=False, server_default='0.10'),
        sa.Column('seuil_malus_normal', sa.Float(), nullable=False, server_default='0.30'),
        sa.Column('seuil_malus_souple', sa.Float(), nullable=False, server_default='0.50'),
        # Seuils bonus (% du cycle en retard pour entrer en zone bonus)
        sa.Column('seuil_bonus_strict', sa.Float(), nullable=False, server_default='0.10'),
        sa.Column('seuil_bonus_normal', sa.Float(), nullable=False, server_default='0.20'),
        sa.Column('seuil_bonus_souple', sa.Float(), nullable=False, server_default='0.40'),
        # Plancher malus (multiplicateur minimum a 100% d'avance)
        sa.Column('plancher_malus', sa.Float(), nullable=False, server_default='0.50'),
        # Bonus cliff (multiplicateur supplementaire en zone bonus)
        sa.Column('bonus_pct', sa.Float(), nullable=False, server_default='0.50'),
        # Analyse 'systematiquement en retard'
        sa.Column('bump_interval_factor_k', sa.Float(), nullable=False, server_default='3.0'),
        sa.Column('bump_y_min_days', sa.Integer(), nullable=False, server_default='90'),
        sa.Column('bump_y_max_days', sa.Integer(), nullable=False, server_default='1095'),
        sa.Column('bump_z_min', sa.Integer(), nullable=False, server_default='3'),
        sa.Column('bump_x_ratio', sa.Float(), nullable=False, server_default='0.70'),
        sa.Column('bump_grace_ratio', sa.Float(), nullable=False, server_default='0.70'),
        sa.Column(
            'updated_at',
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.ForeignKeyConstraint(['foyer_id'], ['foyers.id'], ondelete='CASCADE'),
        sa.PrimaryKeyConstraint('foyer_id'),
    )

    # Remplir la config par defaut pour chaque foyer existant.
    op.execute("""
        INSERT INTO foyer_bonus_config (foyer_id)
        SELECT id FROM foyers
        WHERE deleted_at IS NULL
        ON CONFLICT (foyer_id) DO NOTHING
    """)


def downgrade() -> None:
    op.drop_table('foyer_bonus_config')
    op.drop_column('task_completions', 'reason')
    op.drop_column('task_completions', 'multiplier')
    postgresql.ENUM(name='completion_reason').drop(op.get_bind(), checkfirst=True)
