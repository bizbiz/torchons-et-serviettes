"""Suppression de `rooms` et `categories` — les contextes deviennent la seule source.

Revision ID: e5f6a1b2c3d4
Revises: d4e5f6a0b1c2
Create Date: 2026-08-19

La double ecriture avait un but precis et temporaire : laisser l'APK diffuse
continuer de lire `rooms` / `categories` / `tasks.room_id` / `tasks.category_id`
le temps que le parc tourne. Cette migration la retire.

CE QU'ELLE VERIFIE AVANT DE SUPPRIMER
-------------------------------------
Aucune suppression n'est faite a l'aveugle. La migration echoue — plutot que
de perdre des donnees en silence — si une tache non supprimee reste sans
aucun contexte alors qu'elle avait une piece ou une categorie. C'est le seul
scenario ou la suppression perdrait de l'information : une tache dont le seul
rattachement vivait dans l'ancien modele.

Le controle est volontairement bloquant. Une migration destructrice qui
« repare en silence » ce qu'elle ne comprend pas est pire qu'une migration qui
s'arrete : elle transforme un probleme visible en donnee corrompue.

CE QU'ELLE FAIT DU SCOPE DES OBJECTIFS
--------------------------------------
`objectives.scope_room_id` et `scope_category_id` deviennent un unique
`scope_context_id`. Mesure avant ecriture, sur la production : 1 objectif au
total, 0 portant l'un ou l'autre scope. La conversion ne peut donc rien
perdre — et si un scope apparaissait entre-temps, il est repris via le
contexte de meme nom.

ORDRE DE DEPLOIEMENT
--------------------
Cette migration exige que le client deploye n'utilise PLUS `room_id` /
`category_id`. Elle vient donc APRES la diffusion d'un APK bascule sur les
contextes, pas avant (cf. CLAUDE.md § Sync ordering).
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'e5f6a1b2c3d4'
down_revision: Union[str, None] = 'd4e5f6a0b1c2'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    conn = op.get_bind()

    # --- Garde-fou : aucune tache ne doit perdre son seul rattachement ---
    orphelines = conn.execute(sa.text("""
        SELECT count(*) FROM tasks t
        WHERE t.deleted_at IS NULL
          AND (t.room_id IS NOT NULL OR t.category_id IS NOT NULL)
          AND NOT EXISTS (SELECT 1 FROM task_contexts tc WHERE tc.task_id = t.id)
    """)).scalar_one()
    if orphelines:
        raise RuntimeError(
            f"{orphelines} tache(s) ont une piece ou une categorie mais aucun "
            "contexte : les supprimer perdrait leur seul rattachement. "
            "Rejouer la migration c3d4e5f6a9b0 ou rattacher ces taches a la "
            "main avant de relancer."
        )

    # --- Scope des objectifs : deux colonnes deviennent une ---
    op.add_column(
        'objectives',
        sa.Column('scope_context_id', sa.dialects.postgresql.UUID(as_uuid=True),
                  sa.ForeignKey('contexts.id', ondelete='SET NULL'), nullable=True),
    )
    # Report par NOM : c'est ce qu'a utilise la migration de fusion, donc le
    # seul lien qui existe entre une ancienne piece et son contexte.
    op.execute("""
        UPDATE objectives o SET scope_context_id = c.id
        FROM rooms r JOIN contexts c
          ON c.foyer_id = r.foyer_id AND lower(trim(c.name)) = lower(trim(r.name))
        WHERE o.scope_room_id = r.id
    """)
    op.execute("""
        UPDATE objectives o SET scope_context_id = c.id
        FROM categories k JOIN contexts c
          ON c.foyer_id = k.foyer_id AND lower(trim(c.name)) = lower(trim(k.name))
        WHERE o.scope_category_id = k.id AND o.scope_context_id IS NULL
    """)
    op.drop_column('objectives', 'scope_room_id')
    op.drop_column('objectives', 'scope_category_id')

    # --- Les taches ne portent plus de piece ni de categorie ---
    op.drop_column('tasks', 'room_id')
    op.drop_column('tasks', 'category_id')

    # --- Les deux tables disparaissent ---
    op.drop_table('rooms')
    op.drop_table('categories')


def downgrade() -> None:
    """Recree la structure, PAS les donnees.

    Un downgrade rendrait des tables vides et des colonnes NULL : les pieces et
    categories d'origine ne sont plus nulle part. Il n'existe que pour ne pas
    laisser la chaine de migrations sans retour possible, et pour permettre un
    redeploiement de l'ancien code sur une base neuve — jamais pour recuperer
    des donnees.
    """
    op.create_table(
        'categories',
        sa.Column('id', sa.dialects.postgresql.UUID(as_uuid=True), primary_key=True,
                  server_default=sa.text('gen_random_uuid()')),
        sa.Column('foyer_id', sa.dialects.postgresql.UUID(as_uuid=True),
                  sa.ForeignKey('foyers.id', ondelete='CASCADE'), nullable=False),
        sa.Column('name', sa.String(100), nullable=False),
        sa.Column('color', sa.String(7), server_default='#6B7280'),
        sa.Column('icon', sa.String(50), nullable=True),
        sa.Column('sort_order', sa.Integer(), server_default='0'),
        sa.Column('requires_home', sa.Boolean(), nullable=False, server_default='false'),
        sa.Column('template_key', sa.String(50), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column('deleted_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('version', sa.Integer(), nullable=False, server_default='1'),
    )
    op.create_table(
        'rooms',
        sa.Column('id', sa.dialects.postgresql.UUID(as_uuid=True), primary_key=True,
                  server_default=sa.text('gen_random_uuid()')),
        sa.Column('foyer_id', sa.dialects.postgresql.UUID(as_uuid=True),
                  sa.ForeignKey('foyers.id', ondelete='CASCADE'), nullable=False),
        sa.Column('name', sa.String(100), nullable=False),
        sa.Column('color', sa.String(7), server_default='#6B7280'),
        sa.Column('icon', sa.String(50), nullable=True),
        sa.Column('emoji', sa.String(10), nullable=True),
        sa.Column('template_key', sa.String(50), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column('deleted_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('version', sa.Integer(), nullable=False, server_default='1'),
    )
    op.add_column('tasks', sa.Column(
        'room_id', sa.dialects.postgresql.UUID(as_uuid=True),
        sa.ForeignKey('rooms.id', ondelete='SET NULL'), nullable=True))
    op.add_column('tasks', sa.Column(
        'category_id', sa.dialects.postgresql.UUID(as_uuid=True),
        sa.ForeignKey('categories.id', ondelete='SET NULL'), nullable=True))
    op.add_column('objectives', sa.Column(
        'scope_room_id', sa.dialects.postgresql.UUID(as_uuid=True),
        sa.ForeignKey('rooms.id', ondelete='SET NULL'), nullable=True))
    op.add_column('objectives', sa.Column(
        'scope_category_id', sa.dialects.postgresql.UUID(as_uuid=True),
        sa.ForeignKey('categories.id', ondelete='SET NULL'), nullable=True))
    op.drop_column('objectives', 'scope_context_id')
