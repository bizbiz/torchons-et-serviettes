"""home_ssids per-foyer (deplace de user_settings vers user_foyers).

Revision ID: h6c7d8e9f0a1
Revises: g5b6c7d8e9f0
Create Date: 2026-04-14

Contexte : avant v0.7.x, home_ssids etait stocke sur user_settings (per-user
global). Un user dans 2 foyers avait donc la meme liste de SSIDs maison
pour les 2 — ce qui n'a aucun sens, chaque foyer est un lieu physique
different. On deplace la colonne sur user_foyers (per-user-per-foyer) et
on migre les donnees existantes vers le foyer actif de chaque user.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'h6c7d8e9f0a1'
down_revision: Union[str, None] = 'g5b6c7d8e9f0'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # Ajouter la colonne home_ssids sur user_foyers, par defaut vide.
    op.add_column(
        'user_foyers',
        sa.Column('home_ssids', sa.Text(), server_default='', nullable=False),
    )

    # Backfill : pour chaque ligne user_settings avec home_ssids non-vide,
    # copier vers user_foyers(user_id, last_active_foyer_id). Si l'user n'a
    # pas de last_active_foyer_id (impossible apres v0.7.0 mais safety net),
    # on ignore la ligne.
    op.execute(
        """
        UPDATE user_foyers uf
        SET home_ssids = us.home_ssids
        FROM user_settings us, users u
        WHERE us.user_id = u.id
          AND uf.user_id = u.id
          AND uf.foyer_id = u.last_active_foyer_id
          AND us.home_ssids IS NOT NULL
          AND us.home_ssids <> '';
        """
    )

    # Drop la colonne home_ssids de user_settings (donnees deja backfillees).
    op.drop_column('user_settings', 'home_ssids')


def downgrade() -> None:
    op.add_column(
        'user_settings',
        sa.Column('home_ssids', sa.Text(), server_default='', nullable=False),
    )
    # Restore best-effort depuis le foyer actif de chaque user.
    op.execute(
        """
        UPDATE user_settings us
        SET home_ssids = uf.home_ssids
        FROM users u, user_foyers uf
        WHERE us.user_id = u.id
          AND uf.user_id = u.id
          AND uf.foyer_id = u.last_active_foyer_id;
        """
    )
    op.drop_column('user_foyers', 'home_ssids')
