"""prompt_type : ajout DISCOVER_MODULE (P4 — propositions contextuelles).

Revision ID: b2c3d4e5f6a8
Revises: a1b2c3d4e5f7
Create Date: 2026-08-18

Sixieme type de la queue user_prompts, et le premier qui ne demande pas une
information a l'utilisateur : il lui PROPOSE d'elargir le perimetre de l'app.

Sa regle de diffusion est inversee par rapport aux cinq autres. Un
BUMP_EFFORT n'est servi que si son module est ACTIF ; un DISCOVER_MODULE
n'est servi que si le module qu'il propose est ETEINT — proposer d'activer
ce qui est deja actif n'a aucun sens. Cette inversion est portee par le code
(`app/api/prompts.py`), pas par le schema.

Le `context` JSONB fige a la creation : module_key, titre, pitch, exemple et
raison du declenchement. Meme convention que les autres types — le front
affiche le contexte tel quel, sans recalcul.
"""
from typing import Sequence, Union

from alembic import op


revision: str = 'b2c3d4e5f6a8'
down_revision: Union[str, None] = 'a1b2c3d4e5f7'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.execute("ALTER TYPE prompt_type ADD VALUE IF NOT EXISTS 'DISCOVER_MODULE'")


def downgrade() -> None:
    # Meme raison que la migration x2l3m4n5o6p7 : PostgreSQL ne sait pas
    # retirer une valeur d'enum sans recreer le type. No-op assume.
    pass
