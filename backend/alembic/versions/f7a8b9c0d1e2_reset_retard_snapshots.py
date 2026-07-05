"""reset des snapshots de retard (realignement modele v0.37)

Le calcul de l'historique de retard (foyer_retard_snapshots) a ete realigne
sur GET /api/retard : perimetre = toutes les taches (plus de filtre "taches
en commun"), etat RED jour-granulaire au lieu de l'approximation par
intervalle moyen, et home freeze v2 applique historiquement.

Les snapshots existants ont ete calcules avec l'ancien modele : ils sont
donc perimes. On vide la table — c'est un cache derive, integralement
reconstruit a la volee par backfill_retard (auto-backfill lazy a la
prochaine consultation de la timeline, + cron 03h15).

Aucune perte de donnee : rien ici n'est source de verite.

Revision ID: f7a8b9c0d1e2
Revises: e1f2a3b4c5d6
"""
from typing import Sequence, Union

from alembic import op

revision: str = 'f7a8b9c0d1e2'
down_revision: Union[str, None] = 'e1f2a3b4c5d6'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # Cache derive : on vide, il se reconstruit avec le nouveau modele.
    op.execute("TRUNCATE TABLE foyer_retard_snapshots")


def downgrade() -> None:
    # Rien a faire : le contenu se reconstruit tout seul. Un downgrade ne
    # peut de toute facon pas restaurer l'ancien contenu (cache).
    pass
