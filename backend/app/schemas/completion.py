"""Schemas Pydantic pour les completions de taches."""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from pydantic import BaseModel


class CompletionCreate(BaseModel):
    completed_by: UUID | None = None  # NULL = utilisateur courant
    completed_at: datetime | None = None  # NULL = maintenant, sinon date historique
    notes: str | None = None
    client_supplied_id: UUID | None = None
    client_op_id: UUID | None = None


class CollectiveCompletionCreate(BaseModel):
    """Payload pour le endpoint 'fait ensemble' (N participants).

    Min 1 participant. Pour N >= 2, les points de la tache sont divises
    (ceil integer) entre les participants, une ligne task_completions est
    creee par participant avec le meme collective_id UUID.
    """

    participant_user_ids: list[UUID]
    completed_at: datetime | None = None  # NULL = maintenant
    notes: str | None = None
    client_op_id: UUID | None = None


class CompletionResponse(BaseModel):
    id: UUID
    task_id: UUID
    completed_by: UUID
    completed_at: datetime
    points_earned: int
    multiplier: float
    reason: str  # CompletionReason enum value
    notes: str | None
    collective_id: UUID | None = None

    model_config = {"from_attributes": True}


class CollectiveCompletionResponse(BaseModel):
    collective_id: UUID
    completions: list[CompletionResponse]

    model_config = {"from_attributes": True}


class CompletionPreview(BaseModel):
    """Preview du calcul bonus/malus avant validation de la completion.

    Appele par le dialog 'Deja fait' / 'Fait ensemble' pour afficher a l'user
    combien de points il va gagner et pourquoi (bonus retard / malus avance /
    neutre / 1ere completion).
    """

    base_points: int  # points de base avant multiplicateur (et avant split)
    multiplier: float  # coefficient applique
    final_points: int  # points finaux (apres multiplicateur, avant split collective)
    reason: str  # CompletionReason enum value
    # Metadonnees pour affichage (preview UI : libelles, etc.)
    days_early: int
    days_late: int
    pct_cycle: float  # fraction du cycle (positif = retard, negatif = avance)
