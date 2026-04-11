"""Enums partages cote modeles SQLAlchemy."""

import enum


class TaskTolerance(str, enum.Enum):
    """Tolerance au retard d'une tache.

    Multiplicateur applique a l'intervalle nominal de la recurrence pour
    obtenir la demi-vie de la freshness :
    - STRICT : x0.5  (freshness ~25% a l'echeance nominale)
    - NORMAL : x1.0  (freshness  50% a l'echeance nominale)
    - SOUPLE : x2.0  (freshness ~71% a l'echeance nominale)
    """

    STRICT = "STRICT"
    NORMAL = "NORMAL"
    SOUPLE = "SOUPLE"
