"""Contextes — fusion de Category et Room (chantier contextes).

Revision ID: c3d4e5f6a9b0
Revises: b2c3d4e5f6a8
Create Date: 2026-08-18

Cree `contexts` et `task_contexts`, et y migre l'existant. Les deux anciennes
tables etaient deja quasi identiques : meme foyer_id, name, color, icon,
template_key ; elles ne differaient que d'un champ chacune. Les fusionner ne
perd rien parce qu'une tache peut desormais en declarer PLUSIEURS.

CE QUE LA MIGRATION NE FAIT PAS, ET POURQUOI

Elle ne supprime NI `categories` NI `rooms`, et ne touche pas a
`tasks.category_id` / `tasks.room_id`. L'APK diffuse lit ces colonnes : les
retirer maintenant casserait toutes les installations en cours (regle d'ordre
de synchronisation, cf. docs/architecture.md § Sync ordering). Le serveur ecrit donc en double jusqu'a
diffusion du nouveau client, exactement comme pour
`foyers.gamification_enabled`. Une migration ulterieure fera le menage.

DEDUPLICATION

Un foyer peut avoir une piece « Cuisine » ET une categorie « Cuisine » — c'est
le cas du catalogue historique (`cuisine` / `cuisine_cat`). Sans regroupement,
la fusion produirait deux contextes homonymes dans le meme foyer. On regroupe
donc sur `lower(trim(name))` — pas de repli d'accents, qui exigerait
l'extension `unaccent` et donc des droits superutilisateur sur Scaleway ; le
cas reel (« Cuisine » piece contre « Cuisine » categorie) porte les memes
accents. Le contexte issu de la PIECE gagne : `emoji` et le rattachement des taches
y sont plus riches.

LES DEUX DRAPEAUX

`requires_presence` reprend `categories.requires_home`, qui repondait deja a
cette question. `accrues_when_away` est neuf et ne peut pas se deduire de
l'existant : il est initialise a false partout, puis corrige pour les contextes
dont le nom correspond a un template ou l'on sait que la vie continue sans nous
(jardin, potager, terrasse, balcon, compost, piscine, animaux, administratif).
Un foyer qui a renomme sa piece garde false — c'est le defaut prudent : ne pas
geler serait plus sur, mais false = on gele, donc on reste sur le comportement
ACTUEL et personne ne voit son app changer sans l'avoir demande.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'c3d4e5f6a9b0'
down_revision: Union[str, None] = 'b2c3d4e5f6a8'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


#: Templates dont le besoin monte pendant une absence. Le calendrier tourne,
#: l'herbe pousse, les animaux mangent — que le foyer soit la ou non.
ACCRUES = (
    'jardin', 'potager', 'terrasse', 'balcon', 'compost', 'piscine',
    'administratif', 'soins', 'sante_et_soins', 'plantes_interieur',
    'chat', 'chien', 'rongeur', 'oiseau', 'poissons', 'poules',
    'voiture', 'bebe', 'courses', 'jardinage', 'animal', 'soin_personnel',
)


def upgrade() -> None:
    op.create_table(
        'contexts',
        sa.Column('id', sa.dialects.postgresql.UUID(as_uuid=True), primary_key=True,
                  server_default=sa.text('gen_random_uuid()')),
        sa.Column('foyer_id', sa.dialects.postgresql.UUID(as_uuid=True),
                  sa.ForeignKey('foyers.id', ondelete='CASCADE'), nullable=False),
        sa.Column('name', sa.String(100), nullable=False),
        sa.Column('color', sa.String(7), server_default='#6B7280'),
        sa.Column('icon', sa.String(50), nullable=True),
        sa.Column('emoji', sa.String(10), nullable=True),
        sa.Column('sort_order', sa.Integer(), server_default='0'),
        sa.Column('requires_presence', sa.Boolean(), nullable=False, server_default='false'),
        sa.Column('accrues_when_away', sa.Boolean(), nullable=False, server_default='false'),
        sa.Column('template_key', sa.String(50), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column('deleted_at', sa.DateTime(timezone=True), nullable=True),
    )
    op.create_index('ix_contexts_foyer', 'contexts', ['foyer_id'])

    op.create_table(
        'task_contexts',
        sa.Column('task_id', sa.dialects.postgresql.UUID(as_uuid=True),
                  sa.ForeignKey('tasks.id', ondelete='CASCADE'), primary_key=True),
        sa.Column('context_id', sa.dialects.postgresql.UUID(as_uuid=True),
                  sa.ForeignKey('contexts.id', ondelete='CASCADE'), primary_key=True),
        sa.Column('position', sa.Integer(), nullable=False, server_default='0'),
    )
    op.create_index('ix_task_contexts_context', 'task_contexts', ['context_id'])

    # --- 1. Les PIECES deviennent des contextes ---
    # Elles passent en premier pour gagner la deduplication sur le nom : elles
    # portent l'emoji et le rattachement le plus riche.
    op.execute("""
        INSERT INTO contexts (id, foyer_id, name, color, icon, emoji, sort_order,
                              requires_presence, accrues_when_away, template_key,
                              created_at, updated_at, deleted_at)
        SELECT gen_random_uuid(), r.foyer_id, r.name, r.color, r.icon, r.emoji, 0,
               TRUE, FALSE, r.template_key, now(), now(), r.deleted_at
        FROM rooms r
    """)

    # --- 2. Les CATEGORIES, sauf celles dont le nom existe deja ---
    op.execute("""
        INSERT INTO contexts (id, foyer_id, name, color, icon, emoji, sort_order,
                              requires_presence, accrues_when_away, template_key,
                              created_at, updated_at, deleted_at)
        SELECT gen_random_uuid(), c.foyer_id, c.name, c.color, c.icon, NULL,
               c.sort_order, c.requires_home, FALSE, c.template_key,
               now(), now(), c.deleted_at
        FROM categories c
        WHERE NOT EXISTS (
            SELECT 1 FROM contexts x
            WHERE x.foyer_id = c.foyer_id
              AND lower(trim(x.name)) = lower(trim(c.name))
        )
    """)

    # --- 3. accrues_when_away ---
    # D'abord par template_key, qui est fiable.
    op.execute(sa.text("""
        UPDATE contexts SET accrues_when_away = TRUE
        WHERE template_key = ANY(:keys)
    """).bindparams(sa.bindparam('keys', value=list(ACCRUES))))

    # Puis par NOM, pour les contextes crees a la main — mesure sur les donnees
    # reelles : 20 contextes sur 28 n'ont aucun template_key, dont la totalite
    # du foyer principal. Sans ce rattrapage, « Admin » et « Courses » gelent
    # pendant les vacances alors que le calendrier, lui, continue de tourner.
    #
    # Liste courte et sans ambiguite volontairement : « Perso », « Maison » ou
    # « Hygiene » peuvent vouloir dire trop de choses, ils restent a false. Le
    # faux negatif est le bon cote de l'erreur ici — false = on gele = le
    # comportement actuel, donc personne ne voit son app changer sans l'avoir
    # demande, et le reglage reste corrigeable dans l'app.
    op.execute(sa.text("""
        UPDATE contexts SET accrues_when_away = TRUE
        WHERE template_key IS NULL
          AND lower(trim(name)) = ANY(:names)
    """).bindparams(sa.bindparam('names', value=[
        'admin', 'administratif', 'courses', 'animal', 'animaux',
        'jardin', 'jardinage', 'potager', 'terrasse', 'balcon', 'piscine',
        'compost', 'voiture', 'bebe', 'bébé',
    ])))

    # --- 4. Rattachement des taches ---
    # position 0 = la piece (le lieu porte plus d'information pour l'affichage),
    # position 1 = la categorie. Une tache sans piece prend sa categorie en 0.
    op.execute("""
        INSERT INTO task_contexts (task_id, context_id, position)
        SELECT t.id, x.id, 0
        FROM tasks t
        JOIN rooms r ON r.id = t.room_id
        JOIN contexts x ON x.foyer_id = t.foyer_id
                       AND lower(trim(x.name)) = lower(trim(r.name))
        ON CONFLICT DO NOTHING
    """)
    op.execute("""
        INSERT INTO task_contexts (task_id, context_id, position)
        SELECT t.id, x.id,
               CASE WHEN t.room_id IS NULL THEN 0 ELSE 1 END
        FROM tasks t
        JOIN categories c ON c.id = t.category_id
        JOIN contexts x ON x.foyer_id = t.foyer_id
                       AND lower(trim(x.name)) = lower(trim(c.name))
        ON CONFLICT DO NOTHING
    """)


def downgrade() -> None:
    op.drop_index('ix_task_contexts_context', table_name='task_contexts')
    op.drop_table('task_contexts')
    op.drop_index('ix_contexts_foyer', table_name='contexts')
    op.drop_table('contexts')
