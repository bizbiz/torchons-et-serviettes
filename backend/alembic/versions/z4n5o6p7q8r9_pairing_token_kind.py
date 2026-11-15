"""pairing_tokens.kind : foyer_join vs add_device (v0.31).

Revision ID: z4n5o6p7q8r9
Revises: y3m4n5o6p7q8
Create Date: 2026-04-30

Ajoute une colonne `kind` a la table `pairing_tokens` pour distinguer :
- `foyer_join` (defaut) : QR pour inviter un nouveau membre dans le foyer.
  Cree un nouveau User + UserFoyer + recovery_code (cf. v0.30.0).
- `add_device` : QR pour ajouter un de mes propres appareils (tablette,
  2nd tel) au compte existant. Retourne un JWT pour le `created_by_user_id`
  du token, sans creer de nouveau User.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'z4n5o6p7q8r9'
down_revision: Union[str, None] = 'y3m4n5o6p7q8'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        'pairing_tokens',
        sa.Column(
            'kind', sa.String(20), nullable=False, server_default='foyer_join',
        ),
    )


def downgrade() -> None:
    op.drop_column('pairing_tokens', 'kind')
