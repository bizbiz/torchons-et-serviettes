"""prompt_type : ajout VOTE_FREQUENCY + VOTE_EFFORT.

Revision ID: x2l3m4n5o6p7
Revises: w1k2l3m4n5o6
Create Date: 2026-04-28

v0.28 — sprint 2/2 du chantier vote-first. Deux nouvelles valeurs dans
l'enum PostgreSQL `prompt_type` pour permettre a un user de demander
explicitement a un autre member de revoter sur la frequence ou l'effort
d'une tache.
"""
from typing import Sequence, Union

from alembic import op


revision: str = 'x2l3m4n5o6p7'
down_revision: Union[str, None] = 'w1k2l3m4n5o6'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # ALTER TYPE ... ADD VALUE doit etre execute hors transaction sur
    # certaines versions de Postgres. On utilise IF NOT EXISTS pour rester
    # idempotent (sans incidence sur les versions plus recentes).
    op.execute("ALTER TYPE prompt_type ADD VALUE IF NOT EXISTS 'VOTE_FREQUENCY'")
    op.execute("ALTER TYPE prompt_type ADD VALUE IF NOT EXISTS 'VOTE_EFFORT'")


def downgrade() -> None:
    # PostgreSQL ne supporte pas DROP VALUE sur un enum sans recreer le
    # type entier (DELETE/UPDATE prerequis pour les valeurs en usage).
    # Volontairement no-op : si on regrette, on filtrera cote application
    # ou on fera une migration dediee a ce moment-la.
    pass
