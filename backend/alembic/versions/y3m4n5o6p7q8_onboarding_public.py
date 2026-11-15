"""Plomberie onboarding sans compte + RGPD + premium (v0.30).

Revision ID: y3m4n5o6p7q8
Revises: x2l3m4n5o6p7
Create Date: 2026-04-29

Prepare la transition Tailscale -> Play Store + Scaleway public.

Changements :
- `users.recovery_code_hash`, `recovery_code_last_used_at` : code argon2id
  pour restaurer un foyer si on perd son tel (Q5 du design review).
- `users.deleted_at` : RGPD soft-delete (Q11).
- `foyers.is_premium`, `premium_until` : entitlement premium par foyer (Q7),
  plomberie pour activation paywall en post-launch.
- `foyers.archived_at` : foyer fantome >90j inactif (Q9f).
- `user_foyers.left_at` : soft-leave d'un membre (Q9). Conserve historique
  + votes ; consensus recompute en excluant les former_members.
- Nouvelle table `pairing_tokens` : tokens ephemeres single-use pour le
  flow QR pairing (Q3). TTL 15 min.

Pas de drop de l'ancien `foyers.invite_code` : on le laisse pour compat
tant que les quelques foyers dogfood ne sont pas migres. Sera retire dans
une migration ulterieure quand le code reposera entierement sur
`pairing_tokens`.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'y3m4n5o6p7q8'
down_revision: Union[str, None] = 'x2l3m4n5o6p7'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # --- users : recovery code + soft-delete RGPD ---
    op.add_column(
        'users',
        sa.Column('recovery_code_hash', sa.String(length=255), nullable=True),
    )
    op.add_column(
        'users',
        sa.Column(
            'recovery_code_last_used_at',
            sa.DateTime(timezone=True),
            nullable=True,
        ),
    )
    op.add_column(
        'users',
        sa.Column('deleted_at', sa.DateTime(timezone=True), nullable=True),
    )

    # --- foyers : premium plomberie + archive ---
    op.add_column(
        'foyers',
        sa.Column(
            'is_premium', sa.Boolean(), nullable=False, server_default='false',
        ),
    )
    op.add_column(
        'foyers',
        sa.Column('premium_until', sa.DateTime(timezone=True), nullable=True),
    )
    op.add_column(
        'foyers',
        sa.Column('archived_at', sa.DateTime(timezone=True), nullable=True),
    )

    # --- user_foyers : soft-leave ---
    op.add_column(
        'user_foyers',
        sa.Column('left_at', sa.DateTime(timezone=True), nullable=True),
    )

    # --- pairing_tokens : nouvelle table ---
    op.create_table(
        'pairing_tokens',
        sa.Column('token', sa.String(length=32), primary_key=True),
        sa.Column(
            'foyer_id', sa.dialects.postgresql.UUID(as_uuid=True),
            sa.ForeignKey('foyers.id', ondelete='CASCADE'),
            nullable=False,
        ),
        sa.Column(
            'created_by_user_id', sa.dialects.postgresql.UUID(as_uuid=True),
            sa.ForeignKey('users.id', ondelete='CASCADE'),
            nullable=False,
        ),
        sa.Column(
            'created_at', sa.DateTime(timezone=True),
            server_default=sa.text('now()'), nullable=False,
        ),
        sa.Column(
            'expires_at', sa.DateTime(timezone=True), nullable=False,
        ),
        sa.Column(
            'consumed_at', sa.DateTime(timezone=True), nullable=True,
        ),
    )
    op.create_index(
        'ix_pairing_tokens_foyer_id',
        'pairing_tokens', ['foyer_id'],
    )
    op.create_index(
        'ix_pairing_tokens_expires_at',
        'pairing_tokens', ['expires_at'],
    )


def downgrade() -> None:
    op.drop_index('ix_pairing_tokens_expires_at', table_name='pairing_tokens')
    op.drop_index('ix_pairing_tokens_foyer_id', table_name='pairing_tokens')
    op.drop_table('pairing_tokens')

    op.drop_column('user_foyers', 'left_at')

    op.drop_column('foyers', 'archived_at')
    op.drop_column('foyers', 'premium_until')
    op.drop_column('foyers', 'is_premium')

    op.drop_column('users', 'deleted_at')
    op.drop_column('users', 'recovery_code_last_used_at')
    op.drop_column('users', 'recovery_code_hash')
