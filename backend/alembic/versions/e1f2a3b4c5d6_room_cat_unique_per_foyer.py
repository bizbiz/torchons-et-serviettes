"""rooms/categories : unicité du nom PAR FOYER (drop l'unique globale)

Dérive de schéma historique : `rooms` et `categories` portaient un
`UNIQUE(name)` GLOBAL (`rooms_name_key` / `categories_name_key`, reliquat
d'un ancien `name unique=True`). Le modèle déclare pourtant un composite
`UNIQUE(name, foyer_id)` (`uq_rooms_name_foyer` / `uq_categories_name_foyer`)
qui n'avait jamais été appliqué en base. Conséquence : deux foyers ne
pouvaient pas partager un nom de pièce/catégorie → 500 (UniqueViolation)
à l'import catalogue lors de la création d'un 2e foyer (wizard add-foyer).

Cette migration aligne la base sur le modèle : drop l'unique globale,
ajoute le composite par-foyer. Idempotent (IF EXISTS / DROP avant ADD).

Revision ID: e1f2a3b4c5d6
Revises: d8e9f0a1b2c3
"""
from typing import Sequence, Union

from alembic import op

revision: str = 'e1f2a3b4c5d6'
down_revision: Union[str, None] = 'd8e9f0a1b2c3'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # 1. Drop des uniques GLOBALES legacy (si présentes).
    op.execute("ALTER TABLE rooms DROP CONSTRAINT IF EXISTS rooms_name_key")
    op.execute(
        "ALTER TABLE categories DROP CONSTRAINT IF EXISTS categories_name_key"
    )
    # 2. (Re)pose le composite par-foyer attendu par le modèle. DROP avant
    #    ADD pour être idempotent si une exécution partielle a déjà créé.
    op.execute(
        "ALTER TABLE rooms DROP CONSTRAINT IF EXISTS uq_rooms_name_foyer"
    )
    op.execute(
        "ALTER TABLE rooms ADD CONSTRAINT uq_rooms_name_foyer "
        "UNIQUE (name, foyer_id)"
    )
    op.execute(
        "ALTER TABLE categories DROP CONSTRAINT IF EXISTS "
        "uq_categories_name_foyer"
    )
    op.execute(
        "ALTER TABLE categories ADD CONSTRAINT uq_categories_name_foyer "
        "UNIQUE (name, foyer_id)"
    )


def downgrade() -> None:
    # Retour à l'unique globale (état legacy). Best-effort : ne peut
    # échouer que si des doublons de nom cross-foyers existent déjà.
    op.execute(
        "ALTER TABLE rooms DROP CONSTRAINT IF EXISTS uq_rooms_name_foyer"
    )
    op.execute(
        "ALTER TABLE categories DROP CONSTRAINT IF EXISTS "
        "uq_categories_name_foyer"
    )
    op.execute(
        "ALTER TABLE rooms ADD CONSTRAINT rooms_name_key UNIQUE (name)"
    )
    op.execute(
        "ALTER TABLE categories ADD CONSTRAINT categories_name_key "
        "UNIQUE (name)"
    )
