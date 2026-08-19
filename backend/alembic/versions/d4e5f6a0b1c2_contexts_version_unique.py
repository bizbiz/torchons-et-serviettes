"""Contextes : colonne `version` et unicite du nom par foyer.

Revision ID: d4e5f6a0b1c2
Revises: c3d4e5f6a9b0
Create Date: 2026-08-19

La table `contexts` a ete creee sans ces deux garde-fous, parce qu'elle ne
servait alors qu'a l'import : personne ne pouvait creer un contexte a la main.
L'ouverture d'un CRUD change ca, et les deux manques deviennent des defauts :

- **`version`** — toutes les entites synchronisees en portent une
  (`SoftDeleteMixin`). Sans elle, le client n'a aucun moyen de savoir que sa
  copie est perimee, et deux telephones qui renomment le meme contexte hors
  ligne se recouvrent en silence.
- **unicite `(foyer_id, name)`** — `rooms` et `categories` l'avaient toutes
  les deux. La migration de fusion dedupliquait a la creation, mais rien
  n'empechait ensuite de recreer « Cuisine » a cote de « Cuisine ».

La deduplication prealable est indispensable et non theorique : la base de
beta a recu des imports repetes pendant la mise au point du chantier. On garde
la ligne la plus ancienne (celle que les taches referencent le plus
probablement) et on redirige les rattachements des doublons vers elle avant de
les supprimer — supprimer d'abord perdrait les liens.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'd4e5f6a0b1c2'
down_revision: Union[str, None] = 'c3d4e5f6a9b0'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        'contexts',
        sa.Column('version', sa.Integer(), nullable=False, server_default='1'),
    )

    # --- Deduplication avant contrainte ---
    # Les rattachements des doublons sont d'abord rediriges vers le gardien.
    op.execute("""
        WITH classe AS (
            SELECT id, foyer_id, lower(trim(name)) AS cle,
                   first_value(id) OVER (
                       PARTITION BY foyer_id, lower(trim(name))
                       ORDER BY created_at, id
                   ) AS gardien
            FROM contexts
        )
        UPDATE task_contexts tc
        SET context_id = c.gardien
        FROM classe c
        WHERE tc.context_id = c.id
          AND c.id <> c.gardien
          AND NOT EXISTS (
              SELECT 1 FROM task_contexts x
              WHERE x.task_id = tc.task_id AND x.context_id = c.gardien
          )
    """)
    # Les liens devenus redondants (la tache pointait deja vers le gardien).
    op.execute("""
        DELETE FROM task_contexts tc
        USING contexts c1, contexts c2
        WHERE tc.context_id = c1.id
          AND c2.foyer_id = c1.foyer_id
          AND lower(trim(c2.name)) = lower(trim(c1.name))
          AND (c2.created_at, c2.id) < (c1.created_at, c1.id)
    """)
    op.execute("""
        DELETE FROM contexts c1
        USING contexts c2
        WHERE c2.foyer_id = c1.foyer_id
          AND lower(trim(c2.name)) = lower(trim(c1.name))
          AND (c2.created_at, c2.id) < (c1.created_at, c1.id)
    """)

    # Index unique PARTIEL, et non contrainte simple : le soft delete est la
    # regle du projet, et une unique qui compte les lignes supprimees rend
    # impossible de recreer un contexte qu'on vient de supprimer — la route
    # verifie l'existence avec `deleted_at IS NULL`, elle passerait, et
    # l'insertion echouerait en 500. Mesure sur `rooms` : la meme contrainte y
    # est simple, donc le defaut y existe aussi (note dans todo.md).
    op.create_index(
        'uq_contexts_name_foyer', 'contexts', ['foyer_id', 'name'],
        unique=True, postgresql_where=sa.text('deleted_at IS NULL'),
    )

    # --- Meme correction sur rooms et categories ---
    # Le defaut y est identique et REEL en production : supprimer une piece
    # puis la recreer sous le meme nom rend 500, parce que la ligne
    # soft-deletee occupe toujours le nom. Passer en index partiel est
    # strictement plus permissif que la contrainte actuelle : aucune donnee
    # existante ne peut le violer.
    for table, ancienne in (
        ('rooms', 'uq_rooms_name_foyer'),
        ('categories', 'uq_categories_name_foyer'),
    ):
        op.drop_constraint(ancienne, table, type_='unique')
        op.create_index(
            ancienne, table, ['foyer_id', 'name'],
            unique=True, postgresql_where=sa.text('deleted_at IS NULL'),
        )


def downgrade() -> None:
    for table, ancienne in (
        ('rooms', 'uq_rooms_name_foyer'),
        ('categories', 'uq_categories_name_foyer'),
    ):
        op.drop_index(ancienne, table_name=table)
        op.create_unique_constraint(ancienne, table, ['name', 'foyer_id'])
    op.drop_index('uq_contexts_name_foyer', table_name='contexts')
    op.drop_column('contexts', 'version')
