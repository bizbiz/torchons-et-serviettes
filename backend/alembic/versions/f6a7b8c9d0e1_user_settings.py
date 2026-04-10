"""Ajout table user_settings.

Revision ID: f6a7b8c9d0e1
Revises: e5f6a7b8c9d0
Create Date: 2026-04-09
"""
from typing import Sequence, Union
from alembic import op
import sqlalchemy as sa

revision: str = 'f6a7b8c9d0e1'
down_revision: Union[str, None] = 'e5f6a7b8c9d0'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        'user_settings',
        sa.Column('user_id', sa.UUID(), nullable=False),
        sa.Column('notif_daily', sa.Boolean(), server_default='true', nullable=False),
        sa.Column('notif_urgent', sa.Boolean(), server_default='true', nullable=False),
        sa.Column('notif_completed', sa.Boolean(), server_default='false', nullable=False),
        sa.Column('notif_hour', sa.Integer(), server_default='9', nullable=False),
        sa.Column('affinity_popup_enabled', sa.Boolean(), server_default='true', nullable=False),
        sa.Column('affinity_expiry_months', sa.Integer(), server_default='6', nullable=False),
        sa.Column('freshness_threshold', sa.Integer(), server_default='10', nullable=False),
        sa.Column('home_ssids', sa.Text(), server_default='', nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
        sa.ForeignKeyConstraint(['user_id'], ['users.id'], ondelete='CASCADE'),
        sa.PrimaryKeyConstraint('user_id'),
    )
    # Creer les settings par defaut pour les users existants
    op.execute("INSERT INTO user_settings (user_id) SELECT id FROM users")


def downgrade() -> None:
    op.drop_table('user_settings')
