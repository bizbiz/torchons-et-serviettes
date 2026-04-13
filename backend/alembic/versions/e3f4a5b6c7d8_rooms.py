"""Ajout table rooms + tasks.room_id + parse des titres existants.

Revision ID: e3f4a5b6c7d8
Revises: d2e3f4a5b6c7
Create Date: 2026-04-13
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'e3f4a5b6c7d8'
down_revision: Union[str, None] = 'd2e3f4a5b6c7'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # Table rooms : entites pieces editables dans Settings.
    # Pas de sort_order : tri alphabetique cote API (uniformite avec
    # la prochaine refonte des categories).
    op.create_table(
        'rooms',
        sa.Column('id', sa.UUID(), nullable=False),
        sa.Column('name', sa.String(length=100), nullable=False),
        sa.Column('color', sa.String(length=7), server_default='#6B7280', nullable=False),
        sa.Column('icon', sa.String(length=50), nullable=True),
        sa.Column('emoji', sa.String(length=10), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
        sa.Column('deleted_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('version', sa.Integer(), server_default='1', nullable=False),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('name'),
    )

    # Colonne tasks.room_id : nullable, FK avec ON DELETE SET NULL
    # (cohérent avec category_id qui devient NULL si la categorie est supprimee).
    op.add_column(
        'tasks',
        sa.Column('room_id', sa.UUID(), nullable=True),
    )
    op.create_foreign_key(
        'fk_tasks_room_id',
        'tasks', 'rooms',
        ['room_id'], ['id'],
        ondelete='SET NULL',
    )

    # Parsing des titres existants pattern "Titre — Piece" ou "Titre - Piece".
    # Strategie :
    # 1. Regex permissive pour capturer n'importe quel tiret entoure d'espaces
    # 2. SPLIT_PART sur le dernier separateur (regex avec lookbehind PostgreSQL)
    # 3. INSERT DISTINCT des pieces extraites, trim
    # 4. UPDATE tasks pour lier + nettoyer le titre
    #
    # On utilise PL/pgSQL pour pouvoir faire le rsplit proprement.
    op.execute(
        """
        DO $$
        DECLARE
            t RECORD;
            sep_pos INTEGER;
            extracted_room TEXT;
            new_title TEXT;
            room_uuid UUID;
        BEGIN
            -- Pour chaque tache active, chercher le dernier separateur " - " ou " — " ou " – "
            FOR t IN
                SELECT id, title FROM tasks
                WHERE deleted_at IS NULL
                  AND title ~ '\\s+[\\-—–]\\s+'
            LOOP
                -- Trouve la position du dernier match du pattern "espace tiret espace".
                -- regexp_matches retourne tous les matches mais on veut le dernier
                -- via regexp_replace + rsplit. Approche pragmatique : on prend le
                -- substring apres le dernier " — " / " - " / " – ".
                extracted_room := trim(
                    regexp_replace(t.title, '^.*\\s+[\\-—–]\\s+', '')
                );
                new_title := trim(
                    regexp_replace(t.title, '\\s+[\\-—–]\\s+[^\\-—–]*$', '')
                );

                IF length(extracted_room) > 0 AND length(new_title) > 0 THEN
                    -- Insert ou recup la room (idempotent grace au unique constraint)
                    INSERT INTO rooms (id, name)
                    VALUES (gen_random_uuid(), extracted_room)
                    ON CONFLICT (name) DO NOTHING;

                    SELECT id INTO room_uuid FROM rooms WHERE name = extracted_room;

                    -- Lie la tache + retire le suffixe du titre
                    UPDATE tasks
                    SET room_id = room_uuid,
                        title = new_title,
                        version = version + 1
                    WHERE id = t.id;
                END IF;
            END LOOP;
        END $$;
        """
    )


def downgrade() -> None:
    # Restaure les titres en re-collant le nom de la piece (best effort).
    op.execute(
        """
        UPDATE tasks t
        SET title = t.title || ' — ' || r.name
        FROM rooms r
        WHERE t.room_id = r.id;
        """
    )
    op.drop_constraint('fk_tasks_room_id', 'tasks', type_='foreignkey')
    op.drop_column('tasks', 'room_id')
    op.drop_table('rooms')
