"""Table user_prompts : queue unifiee de questions a poser aux users.

Revision ID: p4e5f6a7b8c9
Revises: o3d4e5f6a7b8
Create Date: 2026-04-17

Remplace le systeme ad-hoc /pending-affinity + /oldest-rating par une table
priorisee. Types : BUMP_EFFORT (retard systematique) et VOTE_RATING
(notation affinite+effort combinee).
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision: str = 'p4e5f6a7b8c9'
down_revision: Union[str, None] = 'o3d4e5f6a7b8'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

PROMPT_TYPE_VALUES = ('BUMP_EFFORT', 'VOTE_RATING')
PROMPT_RESOLUTION_VALUES = ('DISMISSED', 'ANSWERED', 'EXPIRED')


def upgrade() -> None:
    prompt_type_enum = postgresql.ENUM(*PROMPT_TYPE_VALUES, name='prompt_type', create_type=False)
    prompt_type_enum.create(op.get_bind(), checkfirst=True)

    prompt_resolution_enum = postgresql.ENUM(
        *PROMPT_RESOLUTION_VALUES, name='prompt_resolution', create_type=False,
    )
    prompt_resolution_enum.create(op.get_bind(), checkfirst=True)

    op.create_table(
        'user_prompts',
        sa.Column('id', postgresql.UUID(as_uuid=True), nullable=False,
                  server_default=sa.text('gen_random_uuid()')),
        sa.Column('user_id', postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column('foyer_id', postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column('type', prompt_type_enum, nullable=False),
        sa.Column('priority', sa.Integer(), nullable=False, server_default='5'),
        sa.Column('context', postgresql.JSONB(), nullable=False, server_default='{}'),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False,
                  server_default=sa.func.now()),
        sa.Column('shown_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('resolved_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('resolution', prompt_resolution_enum, nullable=True),
        sa.Column('expires_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('grace_until', sa.DateTime(timezone=True), nullable=True),
        sa.PrimaryKeyConstraint('id'),
        sa.ForeignKeyConstraint(['user_id'], ['users.id']),
        sa.ForeignKeyConstraint(['foyer_id'], ['foyers.id'], ondelete='CASCADE'),
    )

    # Index pour GET /prompts/next (recherche du prompt non resolu le plus prioritaire)
    op.create_index(
        'ix_user_prompts_next',
        'user_prompts',
        ['user_id', 'foyer_id', 'resolved_at', 'priority', 'created_at'],
    )


def downgrade() -> None:
    op.drop_index('ix_user_prompts_next', table_name='user_prompts')
    op.drop_table('user_prompts')
    postgresql.ENUM(name='prompt_resolution').drop(op.get_bind(), checkfirst=True)
    postgresql.ENUM(name='prompt_type').drop(op.get_bind(), checkfirst=True)
