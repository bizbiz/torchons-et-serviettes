"""Table objectives — objectifs personnels et partages (v0.31).

Revision ID: a5b6c7d8e9f0
Revises: z4n5o6p7q8r9
Create Date: 2026-04-30

Cf. roadmap user-facing "Objectifs personnels et partages" et
backend/app/models/objective.py.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'a5b6c7d8e9f0'
down_revision: Union[str, None] = 'z4n5o6p7q8r9'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        'objectives',
        sa.Column(
            'id', sa.dialects.postgresql.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text('uuid_generate_v4()'),
        ),
        sa.Column(
            'foyer_id', sa.dialects.postgresql.UUID(as_uuid=True),
            sa.ForeignKey('foyers.id', ondelete='CASCADE'),
            nullable=False,
        ),
        sa.Column('title', sa.String(120), nullable=False),
        sa.Column(
            'kind', sa.String(16), nullable=False, server_default='shared',
        ),
        sa.Column(
            'period', sa.String(8), nullable=False, server_default='week',
        ),
        sa.Column(
            'target_count', sa.Integer(), nullable=False, server_default='1',
        ),
        sa.Column(
            'scope_room_id', sa.dialects.postgresql.UUID(as_uuid=True),
            sa.ForeignKey('rooms.id', ondelete='SET NULL'), nullable=True,
        ),
        sa.Column(
            'scope_category_id', sa.dialects.postgresql.UUID(as_uuid=True),
            sa.ForeignKey('categories.id', ondelete='SET NULL'), nullable=True,
        ),
        sa.Column(
            'assigned_to_user_id', sa.dialects.postgresql.UUID(as_uuid=True),
            sa.ForeignKey('users.id', ondelete='SET NULL'), nullable=True,
        ),
        sa.Column(
            'is_active', sa.Boolean(), nullable=False, server_default='true',
        ),
        sa.Column(
            'created_at', sa.DateTime(timezone=True),
            server_default=sa.text('now()'),
        ),
        sa.Column(
            'updated_at', sa.DateTime(timezone=True),
            server_default=sa.text('now()'),
        ),
        sa.Column(
            'deleted_at', sa.DateTime(timezone=True), nullable=True,
        ),
        sa.Column(
            'version', sa.Integer(), nullable=False, server_default='1',
        ),
    )
    op.create_index(
        'ix_objectives_foyer_active',
        'objectives', ['foyer_id', 'is_active'],
    )


def downgrade() -> None:
    op.drop_index('ix_objectives_foyer_active', table_name='objectives')
    op.drop_table('objectives')
