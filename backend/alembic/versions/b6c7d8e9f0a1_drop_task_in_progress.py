"""Drop task_in_progress table — code mort depuis v0.11 (v0.31).

Revision ID: b6c7d8e9f0a1
Revises: a5b6c7d8e9f0
Create Date: 2026-04-30

La table `task_in_progress` (alias TaskInProgress) etait utilisee par
les endpoints `POST /tasks/{id}/start`, `DELETE /tasks/{id}/start` et
`GET /in-progress` ainsi que par la card "En cours" du HomeScreen,
retiree en v0.11.0. Plus aucun call-site cote API ni cote Android
depuis cette release.

Roadmap user-facing avait note ce nettoyage : "A retirer lors d'une
iteration de maintenance pour ne pas trainer du code mort".
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'b6c7d8e9f0a1'
down_revision: Union[str, None] = 'a5b6c7d8e9f0'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # IF EXISTS pour idempotence : si la table a deja ete droppee
    # manuellement, la migration ne plante pas.
    op.execute('DROP TABLE IF EXISTS task_in_progress CASCADE')


def downgrade() -> None:
    # Recreation a l'identique de la migration b2c3d4e5f6a7_gamification.
    op.create_table(
        'task_in_progress',
        sa.Column(
            'task_id', sa.dialects.postgresql.UUID(as_uuid=True),
            sa.ForeignKey('tasks.id', ondelete='CASCADE'),
            primary_key=True,
        ),
        sa.Column(
            'user_id', sa.dialects.postgresql.UUID(as_uuid=True),
            sa.ForeignKey('users.id', ondelete='CASCADE'),
            nullable=False,
        ),
        sa.Column(
            'started_at', sa.DateTime(timezone=True),
            server_default=sa.text('now()'), nullable=False,
        ),
    )
