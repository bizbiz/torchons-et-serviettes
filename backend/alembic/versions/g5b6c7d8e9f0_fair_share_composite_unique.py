"""Migration : fair_share_config unique user_id -> composite (user_id, foyer_id).

Revision ID: g5b6c7d8e9f0
Revises: f4a5b6c7d8e9
Create Date: 2026-04-14

Contexte : avant multi-foyer, fair_share_config avait user_id UNIQUE.
Maintenant qu'un user peut etre dans plusieurs foyers, on veut un ratio par
(user, foyer). On drop l'ancienne unique et on cree une composite.
"""
from typing import Sequence, Union

from alembic import op


revision: str = 'g5b6c7d8e9f0'
down_revision: Union[str, None] = 'f4a5b6c7d8e9'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # Drop l'ancienne contrainte unique sur user_id (nom auto-genere par PG).
    # Le nom par defaut suit le pattern "<table>_<column>_key".
    op.drop_constraint('fair_share_config_user_id_key', 'fair_share_config', type_='unique')
    op.create_unique_constraint(
        'uq_fair_share_user_foyer',
        'fair_share_config',
        ['user_id', 'foyer_id'],
    )


def downgrade() -> None:
    op.drop_constraint('uq_fair_share_user_foyer', 'fair_share_config', type_='unique')
    op.create_unique_constraint(
        'fair_share_config_user_id_key',
        'fair_share_config',
        ['user_id'],
    )
