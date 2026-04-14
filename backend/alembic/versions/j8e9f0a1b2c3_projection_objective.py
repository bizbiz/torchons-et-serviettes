"""Ajout objectif perso et unite d'affichage sur user_foyers (v0.8.0 magic buttons).

Revision ID: j8e9f0a1b2c3
Revises: i7d8e9f0a1b2
Create Date: 2026-04-14

Ajoute 2 colonnes sur user_foyers (par user par foyer) :
- daily_objective_minutes : int nullable, l'objectif perso en minutes par jour
  que l'user s'est fixe (via le slider de simulation). Null = pas d'objectif
  defini, on retombe sur le rythme moyen 14j pour l'affichage.
- objective_display_unit : string 'min' ou 'tasks', defaut 'min'. Preference
  d'affichage du slider et des cards (l'user peut vouloir raisonner en
  nombre de taches/jour plutot qu'en minutes).

Per user foyer parce qu'un user peut avoir des objectifs differents dans
ses differents foyers (maison = 45 min/j, colloc vacances = 15 min/j).
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'j8e9f0a1b2c3'
down_revision: Union[str, None] = 'i7d8e9f0a1b2'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        'user_foyers',
        sa.Column('daily_objective_minutes', sa.Integer(), nullable=True),
    )
    op.add_column(
        'user_foyers',
        sa.Column(
            'objective_display_unit',
            sa.String(10),
            server_default=sa.text("'min'"),
            nullable=False,
        ),
    )


def downgrade() -> None:
    op.drop_column('user_foyers', 'objective_display_unit')
    op.drop_column('user_foyers', 'daily_objective_minutes')
