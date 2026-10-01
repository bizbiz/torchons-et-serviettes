"""Schemas Pydantic pour les taches."""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from app.schemas.context import TaskContextRef
from pydantic import BaseModel, Field, model_serializer, model_validator

from app.models.enums import TaskTolerance
from app.services.recurrence import LIVING_RULE_TYPES, normalize_recurrence_rule


class RecurrenceRule(BaseModel):
    """Regle de recurrence d'une tache.

    Accepted types are `LIVING_RULE_TYPES` (services/recurrence.py), the same
    set /sync enforces — REST answered 422 on `calendar` while /sync stored it:
    - `interval` : cadence reguliere, every_n_days obligatoire.
    - `monthly_variable` : cadence variable selon le mois, days_per_month
      obligatoire (12 entrees).
    - `uncertain` : pas de cadence connue, aucun champ de cadence.
    - `calendar` : `months` required (non-empty), `day`, `grace_days` optional.
    - `anniversary` : `every_n_months` required, anchored on the first
      completion (docs/contexts.fr.md § Récurrence calendaire).

    Les anciens types `weekdays`, `monthly`, `custom` ont ete supprimes en
    v0.27. Les regles existantes en base ne portent que les types
    autorises (verifie avant deploiement).
    """
    type: str
    every_n_days: int | None = None
    days_per_month: dict[str, int] | None = None
    months: list[int] | None = None
    day: int | None = None
    grace_days: int | None = None
    every_n_months: int | None = None

    @model_serializer(mode="plain")
    def _stored_form(self) -> dict:
        """What gets stored: normalised, and without null keys.

        Null keys used to be stored on REST writes; the vote lock in PUT
        /tasks compares the incoming rule with the stored one (consensus
        shape, no null keys), and every new optional field would have added
        one more key to that comparison.
        """
        raw = {k: v for k, v in self.__dict__.items() if v is not None}
        return normalize_recurrence_rule(raw)

    @model_validator(mode="after")
    def _validate_type(self) -> "RecurrenceRule":
        if self.type not in LIVING_RULE_TYPES:
            raise ValueError(
                f"type de recurrence non supporte : {self.type!r}. "
                f"Types valides : {sorted(LIVING_RULE_TYPES)}."
            )
        if self.type == "uncertain":
            if self.every_n_days is not None or self.days_per_month is not None:
                raise ValueError(
                    "recurrence 'uncertain' ne doit pas porter de champs de cadence."
                )
        elif self.type == "interval":
            if self.every_n_days is None:
                raise ValueError("recurrence 'interval' doit fournir every_n_days.")
        elif self.type == "monthly_variable":
            if not self.days_per_month:
                raise ValueError(
                    "recurrence 'monthly_variable' doit fournir days_per_month."
                )
        elif self.type == "calendar":
            if not self.months:
                raise ValueError("recurrence 'calendar' must provide months.")
        elif self.type == "anniversary":
            if self.every_n_months is None or self.every_n_months < 1:
                raise ValueError("recurrence 'anniversary' must provide every_n_months >= 1.")
        return self


class TaskCreate(BaseModel):
    title: str = Field(max_length=300)
    description: str | None = None
    #: Contexts to attach, in order — index 0 is the primary one.
    context_ids: list[UUID] | None = None
    assigned_to_ids: list[UUID] = Field(min_length=1)
    recurrence_rule: RecurrenceRule | None = None
    weight: int = Field(default=1, ge=1, le=18)
    tolerance: TaskTolerance = TaskTolerance.NORMAL
    client_supplied_id: UUID | None = None
    client_op_id: UUID | None = None


class TaskUpdate(BaseModel):
    title: str | None = Field(default=None, max_length=300)
    description: str | None = None
    #: Full replacement of the attachment list when present. An empty list
    #: detaches every context; omitting the field leaves them untouched.
    context_ids: list[UUID] | None = None
    assigned_to_ids: list[UUID] | None = Field(default=None, min_length=1)
    recurrence_rule: RecurrenceRule | None = None
    weight: int | None = Field(default=None, ge=1, le=18)
    tolerance: TaskTolerance | None = None
    is_active: bool | None = None
    sort_order: int | None = None


class TaskResponse(BaseModel):
    id: UUID
    title: str
    description: str | None
    #: Ordered attachments, primary first. Empty for a task created before the
    #: contexts chantier and never edited since.
    contexts: list[TaskContextRef] = []
    assigned_to_ids: list[UUID]
    recurrence_rule: dict | None
    inferred_interval_days: float | None = None
    weight: int
    tolerance: TaskTolerance
    is_active: bool
    is_archived: bool = False
    sort_order: int
    created_at: datetime
    updated_at: datetime
    # Home freeze v2 : nombre de jours absents du foyer depuis la derniere
    # completion (ou created_at si jamais faite) jusqu'a aujourd'hui. Vaut 0
    # si la categorie n'est pas requires_home ou si home_freeze_enabled=false.
    # Le client Android decale `today` de ce delta pour le calcul d'urgence.
    absent_days_since_last_completion: int = 0
    # Date de derniere completion non supprimee, calculee serveur (autoritaire
    # quand le client est en ligne). null = jamais completee. Le client
    # Android l'utilise comme source de verite online ; offline il retombe
    # sur le max local du cache des completions. Cf docs/cache-conventions.md.
    last_completed_at: datetime | None = None
    #: Earliest live completion: the anchor of an `anniversary` rule. Null =
    #: never completed. Server-authoritative like last_completed_at; offline
    #: the client takes the min with its local cache.
    first_completed_at: datetime | None = None
