"""Remplace assigned_to par table task_assignments.

Revision ID: a1b2c3d4e5f6
Revises: 42853849d697
Create Date: 2026-04-09
"""
from typing import Sequence, Union
from alembic import op
import sqlalchemy as sa

revision: str = 'a1b2c3d4e5f6'
down_revision: Union[str, None] = '42853849d697'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # 1. Creer la table de jointure
    op.create_table(
        'task_assignments',
        sa.Column('task_id', sa.UUID(), nullable=False),
        sa.Column('user_id', sa.UUID(), nullable=False),
        sa.ForeignKeyConstraint(['task_id'], ['tasks.id'], ondelete='CASCADE'),
        sa.ForeignKeyConstraint(['user_id'], ['users.id'], ondelete='CASCADE'),
        sa.PrimaryKeyConstraint('task_id', 'user_id'),
    )

    # 2. Migrer les donnees existantes
    # Taches avec assigned_to → perso (1 user)
    op.execute("""
        INSERT INTO task_assignments (task_id, user_id)
        SELECT id, assigned_to FROM tasks
        WHERE assigned_to IS NOT NULL AND deleted_at IS NULL
    """)

    # Taches sans assigned_to → communes (les 2 users)
    op.execute("""
        INSERT INTO task_assignments (task_id, user_id)
        SELECT t.id, u.id FROM tasks t CROSS JOIN users u
        WHERE t.assigned_to IS NULL AND t.deleted_at IS NULL
    """)

    # 3. Supprimer la colonne assigned_to
    op.drop_constraint('tasks_assigned_to_fkey', 'tasks', type_='foreignkey')
    op.drop_column('tasks', 'assigned_to')


def downgrade() -> None:
    # Recréer la colonne assigned_to
    op.add_column('tasks', sa.Column('assigned_to', sa.UUID(), nullable=True))
    op.create_foreign_key('tasks_assigned_to_fkey', 'tasks', 'users', ['assigned_to'], ['id'])

    # Migrer les donnees : taches avec 1 seul user → assigned_to = ce user
    op.execute("""
        UPDATE tasks SET assigned_to = ta.user_id
        FROM (
            SELECT task_id, user_id FROM task_assignments
            WHERE task_id IN (
                SELECT task_id FROM task_assignments GROUP BY task_id HAVING COUNT(*) = 1
            )
        ) ta
        WHERE tasks.id = ta.task_id
    """)

    op.drop_table('task_assignments')
