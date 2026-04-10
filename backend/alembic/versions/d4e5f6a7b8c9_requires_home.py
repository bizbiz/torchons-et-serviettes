"""Ajout requires_home sur categories.

Revision ID: d4e5f6a7b8c9
Revises: c3d4e5f6a7b8
Create Date: 2026-04-09
"""
from typing import Sequence, Union
from alembic import op
import sqlalchemy as sa

revision: str = 'd4e5f6a7b8c9'
down_revision: Union[str, None] = 'c3d4e5f6a7b8'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column('categories', sa.Column('requires_home', sa.Boolean(), server_default='false', nullable=False))
    # Mettre requires_home=true pour les categories liees a la maison
    op.execute("UPDATE categories SET requires_home = true WHERE name IN ('Ménage', 'Maison', 'Entretien', 'Hygiene')")


def downgrade() -> None:
    op.drop_column('categories', 'requires_home')
