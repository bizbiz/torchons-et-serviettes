"""Multi-foyer architecture : table foyers, user_foyers, foyer_id sur top-level.

Revision ID: f4a5b6c7d8e9
Revises: e3f4a5b6c7d8
Create Date: 2026-04-14

Strategie :
1. Cree table foyers + user_foyers
2. Insere le foyer "Maison" par defaut (id capture en variable pour backfill)
3. Insere un user_foyer par user existant (tous admin)
4. Ajoute users.last_active_foyer_id
5. Ajoute foyer_id (nullable d'abord pour backfill, puis NOT NULL) sur :
   tasks, categories, rooms, streaks, badges, fair_share_config, change_log
6. Backfill toutes ces tables avec l'id du foyer Maison
7. ALTER TABLE ... ALTER COLUMN foyer_id SET NOT NULL
8. Add FK constraints ON DELETE CASCADE (sauf change_log = SET NULL pour garder
   l'historique si un foyer est detruit en erreur)
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'f4a5b6c7d8e9'
down_revision: Union[str, None] = 'e3f4a5b6c7d8'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # --- Step 1 : table foyers ---
    op.create_table(
        'foyers',
        sa.Column('id', sa.UUID(), server_default=sa.text('gen_random_uuid()'), nullable=False),
        sa.Column('name', sa.String(length=100), nullable=False),
        sa.Column('invite_code', sa.String(length=16), nullable=True),
        sa.Column('home_freeze_enabled', sa.Boolean(), server_default=sa.text('false'), nullable=False),
        sa.Column('last_occupied_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
        sa.Column('deleted_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('version', sa.Integer(), server_default='1', nullable=False),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('invite_code'),
    )

    # --- Step 2 : table user_foyers (junction N:N) ---
    op.create_table(
        'user_foyers',
        sa.Column('user_id', sa.UUID(), nullable=False),
        sa.Column('foyer_id', sa.UUID(), nullable=False),
        sa.Column('role', sa.String(length=20), server_default='member', nullable=False),
        sa.Column('joined_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
        sa.ForeignKeyConstraint(['user_id'], ['users.id'], ondelete='CASCADE'),
        sa.ForeignKeyConstraint(['foyer_id'], ['foyers.id'], ondelete='CASCADE'),
        sa.PrimaryKeyConstraint('user_id', 'foyer_id'),
    )

    # --- Step 3 : insere le foyer "Maison" par defaut et lie tous les users ---
    # On capture l'id du foyer via une CTE pour pouvoir l'utiliser dans les UPDATE
    # des autres tables. Le foyer est cree avec un id genere.
    op.execute(
        """
        INSERT INTO foyers (name) VALUES ('Maison');
        """
    )
    # Lie tous les users existants a ce foyer en tant qu'admin
    op.execute(
        """
        INSERT INTO user_foyers (user_id, foyer_id, role)
        SELECT u.id, f.id, 'admin'
        FROM users u, foyers f
        WHERE f.name = 'Maison';
        """
    )

    # --- Step 4 : users.last_active_foyer_id ---
    op.add_column(
        'users',
        sa.Column('last_active_foyer_id', sa.UUID(), nullable=True),
    )
    op.create_foreign_key(
        'fk_users_last_active_foyer',
        'users', 'foyers',
        ['last_active_foyer_id'], ['id'],
        ondelete='SET NULL',
    )
    op.execute(
        """
        UPDATE users SET last_active_foyer_id = (SELECT id FROM foyers WHERE name = 'Maison');
        """
    )

    # --- Step 5 : ajout foyer_id nullable sur les 7 top-level tables ---
    top_level_tables = [
        'tasks',
        'categories',
        'rooms',
        'streaks',
        'badges',
        'fair_share_config',
        'change_log',
    ]
    for table in top_level_tables:
        op.add_column(table, sa.Column('foyer_id', sa.UUID(), nullable=True))

    # --- Step 6 : backfill avec l'id du foyer Maison ---
    for table in top_level_tables:
        op.execute(
            f"""
            UPDATE {table} SET foyer_id = (SELECT id FROM foyers WHERE name = 'Maison');
            """
        )

    # --- Step 7 : ALTER COLUMN NOT NULL ---
    for table in top_level_tables:
        op.alter_column(table, 'foyer_id', nullable=False)

    # --- Step 8 : FK constraints ---
    # CASCADE : quand on supprime un foyer, on supprime ses tasks/categories/etc.
    # Exception : change_log a ON DELETE SET NULL car l'historique doit survivre
    # (utile en debug). Mais on le force a NOT NULL quand meme ; les nouvelles
    # lignes arriveront toujours avec un foyer_id valide.
    cascade_tables = ['tasks', 'categories', 'rooms', 'streaks', 'badges', 'fair_share_config']
    for table in cascade_tables:
        op.create_foreign_key(
            f'fk_{table}_foyer_id',
            table, 'foyers',
            ['foyer_id'], ['id'],
            ondelete='CASCADE',
        )
    # change_log : RESTRICT pour proteger l'historique
    op.create_foreign_key(
        'fk_change_log_foyer_id',
        'change_log', 'foyers',
        ['foyer_id'], ['id'],
        ondelete='RESTRICT',
    )


def downgrade() -> None:
    # Ordre inverse
    for table in ['tasks', 'categories', 'rooms', 'streaks', 'badges', 'fair_share_config']:
        op.drop_constraint(f'fk_{table}_foyer_id', table, type_='foreignkey')
    op.drop_constraint('fk_change_log_foyer_id', 'change_log', type_='foreignkey')

    for table in ['tasks', 'categories', 'rooms', 'streaks', 'badges', 'fair_share_config', 'change_log']:
        op.drop_column(table, 'foyer_id')

    op.drop_constraint('fk_users_last_active_foyer', 'users', type_='foreignkey')
    op.drop_column('users', 'last_active_foyer_id')

    op.drop_table('user_foyers')
    op.drop_table('foyers')
