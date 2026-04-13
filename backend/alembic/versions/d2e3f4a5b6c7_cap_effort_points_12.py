"""Clamp task_efforts.points a 12 max (aligne sur le slider UI).

Revision ID: d2e3f4a5b6c7
Revises: c1d2e3f4a5b6
Create Date: 2026-04-13
"""
from typing import Sequence, Union

from alembic import op


revision: str = 'd2e3f4a5b6c7'
down_revision: Union[str, None] = 'c1d2e3f4a5b6'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # Alignement avec le slider UI unifie qui cap a 12 (5min-1h).
    # Les votes anciens > 12 (venant du dialog TaskDetail obsolete qui allait
    # jusqu'a 18 = 1h30) sont ramenes a 12. On resynchronise aussi tasks.weight
    # pour les taches affectees.
    op.execute("UPDATE task_efforts SET points = 12 WHERE points > 12")
    op.execute(
        """
        UPDATE tasks
        SET weight = sub.avg_points
        FROM (
            SELECT task_id, ROUND(AVG(points))::int AS avg_points
            FROM task_efforts
            GROUP BY task_id
        ) sub
        WHERE tasks.id = sub.task_id AND tasks.weight IS DISTINCT FROM sub.avg_points
        """
    )


def downgrade() -> None:
    # Pas de rollback : on ne connait plus la valeur originale.
    pass
