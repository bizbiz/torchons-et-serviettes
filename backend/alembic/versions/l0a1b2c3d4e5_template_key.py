"""Ajout colonne template_key sur tasks / rooms / categories (v0.9.0 Feature 4).

Revision ID: l0a1b2c3d4e5
Revises: k9f0a1b2c3d4
Create Date: 2026-04-14

Permet de tracer qu'une tache / piece / categorie vient du catalogue de
templates (backend/app/content/catalog.yaml). Set au moment de l'import
ou via rematch fuzzy plus tard.

Usage futur :
- Tri du catalogue par popularite : COUNT(*) GROUP BY template_key
- Analytics sur les items les plus utilises
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'l0a1b2c3d4e5'
down_revision: Union[str, None] = 'k9f0a1b2c3d4'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        'tasks',
        sa.Column('template_key', sa.String(50), nullable=True),
    )
    op.add_column(
        'rooms',
        sa.Column('template_key', sa.String(50), nullable=True),
    )
    op.add_column(
        'categories',
        sa.Column('template_key', sa.String(50), nullable=True),
    )


def downgrade() -> None:
    op.drop_column('categories', 'template_key')
    op.drop_column('rooms', 'template_key')
    op.drop_column('tasks', 'template_key')
