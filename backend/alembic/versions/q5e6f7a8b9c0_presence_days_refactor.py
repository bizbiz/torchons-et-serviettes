"""Refonte presence : table presence_days + drop colonnes obsoletes.

Revision ID: q5e6f7a8b9c0
Revises: p4e5f6a7b8c9
Create Date: 2026-04-17

Remplace le systeme de detection WiFi background (PresenceWorker) par un
systeme hybride : detection auto au lancement + resolution manuelle des
jours incertains.

Changements :
1. Nouvelle table presence_days (foyer_id, date, status, source)
2. Ajout RESOLVE_PRESENCE dans l'enum prompt_type
3. Drop colonnes obsoletes :
   - users.is_currently_at_home
   - users.last_seen_at_home_at
   - users.wifi_optin
   - foyers.last_occupied_at
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import UUID


revision: str = 'q5e6f7a8b9c0'
down_revision: Union[str, None] = 'p4e5f6a7b8c9'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # 1. Nouvelle table presence_days
    op.create_table(
        'presence_days',
        sa.Column('id', UUID(as_uuid=True), primary_key=True,
                  server_default=sa.text('gen_random_uuid()')),
        sa.Column('foyer_id', UUID(as_uuid=True),
                  sa.ForeignKey('foyers.id', ondelete='CASCADE'), nullable=False),
        sa.Column('date', sa.Date(), nullable=False),
        sa.Column('status', sa.String(20), nullable=False),  # confirmed_present / confirmed_absent
        sa.Column('source', sa.String(20), nullable=False),  # auto_wifi / manual
        sa.Column('created_at', sa.DateTime(timezone=True),
                  server_default=sa.func.now(), nullable=False),
        sa.UniqueConstraint('foyer_id', 'date', name='uq_presence_days_foyer_date'),
    )
    op.create_index('ix_presence_days_foyer_date', 'presence_days', ['foyer_id', 'date'])

    # 2. Ajout RESOLVE_PRESENCE dans l'enum prompt_type
    op.execute("ALTER TYPE prompt_type ADD VALUE IF NOT EXISTS 'RESOLVE_PRESENCE'")

    # 3. Drop colonnes obsoletes
    op.drop_column('users', 'is_currently_at_home')
    op.drop_column('users', 'last_seen_at_home_at')
    op.drop_column('users', 'wifi_optin')
    op.drop_column('foyers', 'last_occupied_at')


def downgrade() -> None:
    # Restaurer colonnes
    op.add_column('foyers', sa.Column(
        'last_occupied_at', sa.DateTime(timezone=True), nullable=True))
    op.add_column('users', sa.Column(
        'wifi_optin', sa.Boolean(), server_default=sa.text('false'), nullable=False))
    op.add_column('users', sa.Column(
        'last_seen_at_home_at', sa.DateTime(timezone=True), nullable=True))
    op.add_column('users', sa.Column(
        'is_currently_at_home', sa.Boolean(), server_default=sa.text('false'), nullable=False))

    op.drop_index('ix_presence_days_foyer_date', table_name='presence_days')
    op.drop_table('presence_days')
    # Note: on ne peut pas retirer une valeur d'un enum PG facilement.
    # RESOLVE_PRESENCE restera dans l'enum apres downgrade.
