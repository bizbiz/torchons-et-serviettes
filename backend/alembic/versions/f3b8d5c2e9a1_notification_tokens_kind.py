"""notification_tokens.kind : jeton FCM ou identifiant d'installation Firebase (FID).

Revision ID: f3b8d5c2e9a1
Revises: e2a7c4b9d1f3
Create Date: 2026-11-12

FCM retire le jeton d'enregistrement (`getToken`, `MulticastMessage.tokens`, dépréciés) au
profit de l'identifiant d'installation Firebase, envoyé par `fids`. L'app passe aux FID avant
le lancement public (décision de Quentin, 2026-11-12) ; les APK déjà installés continuent
d'envoyer un jeton. `kind` dit lequel des deux porte la colonne `fcm_token` : `token` pour
toutes les lignes existantes, `fid` pour celles de l'app nouvelle. Aucune donnée réécrite.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'f3b8d5c2e9a1'
down_revision: Union[str, None] = 'e2a7c4b9d1f3'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        'notification_tokens',
        sa.Column('kind', sa.String(8), nullable=False, server_default='token'),
    )


def downgrade() -> None:
    op.drop_column('notification_tokens', 'kind')
