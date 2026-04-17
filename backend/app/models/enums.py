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


class CompletionReason(str, enum.Enum):
    """Raison du multiplicateur applique a une completion.

    Stockee sur task_completions pour tracabilite historique (immuable).
    Si les seuils changent plus tard dans foyer_bonus_config, les lignes
    historiques gardent leur reason + multiplier figes.

    - NEUTRAL : zone plateau (ni en avance ni en retard significatif), mult=1.0
    - BONUS_RETARD : zone bonus (% retard >= seuil_bonus), mult=1+bonus_pct
    - MALUS_AVANCE : zone malus (% avance > seuil_malus), mult<1.0 (rampe)
    - FIRST_COMPLETION : 1ere completion de la tache, bypass systematique mult=1.0
    - PERSO : tache perso (assignee a 0 ou 1 user), 0 point, mult N/A
    """

    NEUTRAL = "NEUTRAL"
    BONUS_RETARD = "BONUS_RETARD"
    MALUS_AVANCE = "MALUS_AVANCE"
    FIRST_COMPLETION = "FIRST_COMPLETION"
    PERSO = "PERSO"


class PromptType(str, enum.Enum):
    """Types de prompts dans la queue unifiee user_prompts.

    BUMP_EFFORT : tache systematiquement en retard, proposer +1 effort.
    VOTE_RATING : tache sans notation recente (affinite+effort), demander avis.
    """

    BUMP_EFFORT = "BUMP_EFFORT"
    VOTE_RATING = "VOTE_RATING"


class PromptResolution(str, enum.Enum):
    """Resolution d'un prompt par l'utilisateur."""

    DISMISSED = "DISMISSED"
    ANSWERED = "ANSWERED"
    EXPIRED = "EXPIRED"
