"""Gamification opt-out — toggles user-level et foyer-level (v0.32.0).

Revision ID: c7d8e9f0a1b2
Revises: b6c7d8e9f0a1
Create Date: 2026-05-12

Ajoute :
- `user_settings.gamification_enabled` (BOOLEAN, default TRUE) — toggle
  global per-user (couvre tous ses foyers).
- `foyers.gamification_enabled` (BOOLEAN, default TRUE) — toggle per-foyer
  applique a tous ses membres.

Combinaison OR : un user est "sans gamification" si SON toggle est OFF
OU si le toggle du foyer courant est OFF. Le filtrage est 100% cote
frontend (cf grill-me design v0.32.0) — le backend continue de calculer
points, bonus, badges, streaks, fair-share normalement, et expose juste
les toggles via GET/PUT settings + GET/PUT foyer.

Toutes les lignes existantes sont a TRUE (comportement actuel preserve).
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'c7d8e9f0a1b2'
down_revision: Union[str, None] = 'b6c7d8e9f0a1'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # user_settings.gamification_enabled — toggle per-user (global, tous
    # foyers confondus).
    op.add_column(
        'user_settings',
        sa.Column(
            'gamification_enabled',
            sa.Boolean(),
            nullable=False,
            server_default=sa.true(),
        ),
    )
    # foyers.gamification_enabled — toggle per-foyer, applique a tous
    # ses membres.
    op.add_column(
        'foyers',
        sa.Column(
            'gamification_enabled',
            sa.Boolean(),
            nullable=False,
            server_default=sa.true(),
        ),
    )


def downgrade() -> None:
    op.drop_column('foyers', 'gamification_enabled')
    op.drop_column('user_settings', 'gamification_enabled')
