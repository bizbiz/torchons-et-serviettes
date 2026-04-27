"""Remplace notif_completed par trois flags orthogonaux pour les completions.

Revision ID: u9i0j1k2l3m4
Revises: t8h9i0j1k2l3
Create Date: 2026-04-26

Trois flags qui mappent 1:1 sur les trois cas de notification de completion :
- notif_solo : quelqu'un d'autre fait une tache seul.
- notif_collective_with_me : un collectif inclut ce user (et qu'il n'a pas
  enregistre lui-meme).
- notif_collective_without_me : un collectif ne l'inclut pas (visible
  uniquement a partir de 3 membres dans le foyer cote UI).

L'ancien `notif_completed` etait silencieusement ignore par push.py ; les
trois nouveaux flags par defaut FALSE forcent un opt-in explicite. Pas de
data migration : les rows existantes heritent des trois `false` (l'utilisateur
toggle a la main ce qu'il veut recevoir).
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'u9i0j1k2l3m4'
down_revision: Union[str, None] = 't8h9i0j1k2l3'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        'user_settings',
        sa.Column(
            'notif_solo', sa.Boolean(),
            nullable=False, server_default=sa.text('false'),
        ),
    )
    op.add_column(
        'user_settings',
        sa.Column(
            'notif_collective_with_me', sa.Boolean(),
            nullable=False, server_default=sa.text('false'),
        ),
    )
    op.add_column(
        'user_settings',
        sa.Column(
            'notif_collective_without_me', sa.Boolean(),
            nullable=False, server_default=sa.text('false'),
        ),
    )
    op.drop_column('user_settings', 'notif_completed')


def downgrade() -> None:
    op.add_column(
        'user_settings',
        sa.Column(
            'notif_completed', sa.Boolean(),
            nullable=False, server_default=sa.text('false'),
        ),
    )
    op.drop_column('user_settings', 'notif_collective_without_me')
    op.drop_column('user_settings', 'notif_collective_with_me')
    op.drop_column('user_settings', 'notif_solo')
