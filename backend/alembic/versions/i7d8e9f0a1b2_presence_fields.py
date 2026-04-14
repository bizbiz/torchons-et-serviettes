"""Ajout fields presence sur users (v0.7.4 home freeze feature).

Revision ID: i7d8e9f0a1b2
Revises: h6c7d8e9f0a1
Create Date: 2026-04-14

Ajoute 3 colonnes sur users :
- wifi_optin : booleen, l'user a accepte la detection WiFi. Default false
  (explicite : il faut activer dans les reglages).
- is_currently_at_home : booleen, dernier etat reporte par le client.
  Refresh a chaque POST /api/presence.
- last_seen_at_home_at : timestamp, derniere fois ou le client a reporte
  is_at_home=true. Permet au freeze de savoir "depuis quand l'user n'est
  pas rentre".

Pas de modif sur foyers (les colonnes home_freeze_enabled et
last_occupied_at ont deja ete creees dans la migration f4a5b6c7d8e9
de v0.7.0).
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'i7d8e9f0a1b2'
down_revision: Union[str, None] = 'h6c7d8e9f0a1'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        'users',
        sa.Column('wifi_optin', sa.Boolean(), server_default=sa.text('false'), nullable=False),
    )
    op.add_column(
        'users',
        sa.Column('is_currently_at_home', sa.Boolean(), server_default=sa.text('false'), nullable=False),
    )
    op.add_column(
        'users',
        sa.Column('last_seen_at_home_at', sa.DateTime(timezone=True), nullable=True),
    )


def downgrade() -> None:
    op.drop_column('users', 'last_seen_at_home_at')
    op.drop_column('users', 'is_currently_at_home')
    op.drop_column('users', 'wifi_optin')
