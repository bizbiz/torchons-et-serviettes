"""Enums partages cote modeles SQLAlchemy."""

import enum


class TaskTolerance(str, enum.Enum):
    """Tolerance au retard d'une tache — tier de priorite du selecteur home.

    Depuis v0.10.0 (refonte freshness -> modele echeance), tolerance n'a
    plus aucun impact sur l'etat visuel d'une tache dans la liste ni sur
    le fait qu'elle soit en retard. Son seul role est de prioriser le
    selecteur "la plus urgente" affiche sur la page d'accueil :

    - STRICT : tier 1 — une tache strict en retard est toujours proposee
      en priorite, meme si d'autres taches non-strict sont plus en retard.
    - NORMAL : tier 2 — proposee quand aucune strict n'est en retard.
    - SOUPLE : tier 3 — proposee en dernier, quand aucune strict ni normal
      n'est en retard.
    """

    STRICT = "STRICT"
    NORMAL = "NORMAL"
    SOUPLE = "SOUPLE"


class UrgencyState(str, enum.Enum):
    """Etat d'urgence d'une tache, derive de l'ecart today <-> due_date.

    - GREEN : pas encore due (today < due_date - fenetre orange)
    - ORANGE : due aujourd'hui ou dans la fenetre (<= floor(interval*0.10) j
      avant l'echeance)
    - RED : en retard (today > due_date)
    """

    GREEN = "GREEN"
    ORANGE = "ORANGE"
    RED = "RED"
