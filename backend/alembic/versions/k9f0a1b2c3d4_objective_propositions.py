"""Tables objective_propositions + objective_proposition_responses (v0.8.1).

Revision ID: k9f0a1b2c3d4
Revises: j8e9f0a1b2c3
Create Date: 2026-04-14

Feature 3 v0.8.1 : proposition live foyer pour fixer un objectif multi-user
dans le popup simulation. Un proposer pose une proposition avec des
sliders pour chaque user du foyer, les receveurs doivent tous accepter
dans un delai de 3 min pour que les objectifs soient persistes.

Tables :
- objective_propositions : 1 ligne par proposition active
  - id (PK), foyer_id (FK), proposer_id (FK), created_at, expires_at
  - slider_values_json : {user_id: minutes} pour tous les users du foyer
  - status : 'pending' / 'accepted' / 'declined' / 'expired'
- objective_proposition_responses : 1 ligne par user par proposition
  - proposition_id (FK), user_id (FK), PK composite
  - accepted (bool), responded_at

L'expiration est checked-on-read dans les endpoints (pas de worker cron).
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision: str = 'k9f0a1b2c3d4'
down_revision: Union[str, None] = 'j8e9f0a1b2c3'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        'objective_propositions',
        sa.Column('id', postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            'foyer_id',
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey('foyers.id', ondelete='CASCADE'),
            nullable=False,
        ),
        sa.Column(
            'proposer_id',
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey('users.id', ondelete='CASCADE'),
            nullable=False,
        ),
        sa.Column('slider_values_json', postgresql.JSONB, nullable=False),
        sa.Column(
            'status',
            sa.String(20),
            server_default=sa.text("'pending'"),
            nullable=False,
        ),
        sa.Column(
            'created_at',
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column('expires_at', sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index(
        'ix_objective_propositions_foyer_status',
        'objective_propositions',
        ['foyer_id', 'status'],
    )

    op.create_table(
        'objective_proposition_responses',
        sa.Column(
            'proposition_id',
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey('objective_propositions.id', ondelete='CASCADE'),
            primary_key=True,
        ),
        sa.Column(
            'user_id',
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey('users.id', ondelete='CASCADE'),
            primary_key=True,
        ),
        sa.Column('accepted', sa.Boolean(), nullable=False),
        sa.Column(
            'responded_at',
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )


def downgrade() -> None:
    op.drop_table('objective_proposition_responses')
    op.drop_index('ix_objective_propositions_foyer_status', 'objective_propositions')
    op.drop_table('objective_propositions')
