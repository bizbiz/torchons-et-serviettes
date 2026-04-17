"""Routes config bonus/malus par foyer — Options avancees.

GET /api/bonus-config : retourne la config actuelle du foyer courant.
PUT /api/bonus-config : met a jour les parametres (partiel — seuls les
champs presents dans le body sont modifies).

Tous les seuils et parametres sont per-foyer et editables via le panneau
'Options avancees' cote Android. Tooltip "i" sur chaque champ pour
expliquer le sens du parametre.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.services.bonus_malus import load_or_create_bonus_config
from app.utils.scoping import current_foyer_uuid

router = APIRouter(prefix="/api", tags=["bonus-config"])


class BonusConfigResponse(BaseModel):
    """Config bonus/malus du foyer — valeurs actuelles de tous les knobs."""

    # Seuils malus (fraction du cycle en avance pour entrer en zone malus)
    seuil_malus_strict: float
    seuil_malus_normal: float
    seuil_malus_souple: float
    # Seuils bonus (fraction du cycle en retard pour entrer en zone bonus)
    seuil_bonus_strict: float
    seuil_bonus_normal: float
    seuil_bonus_souple: float
    # Plancher et bonus
    plancher_malus: float
    bonus_pct: float
    # Analyse 'systematiquement en retard'
    bump_interval_factor_k: float
    bump_y_min_days: int
    bump_y_max_days: int
    bump_z_min: int
    bump_x_ratio: float
    bump_grace_ratio: float


class BonusConfigUpdate(BaseModel):
    """Mise a jour partielle de la config bonus/malus. Champs optionnels."""

    seuil_malus_strict: float | None = Field(default=None, ge=0.0, le=1.0)
    seuil_malus_normal: float | None = Field(default=None, ge=0.0, le=1.0)
    seuil_malus_souple: float | None = Field(default=None, ge=0.0, le=1.0)
    seuil_bonus_strict: float | None = Field(default=None, ge=0.0, le=1.0)
    seuil_bonus_normal: float | None = Field(default=None, ge=0.0, le=1.0)
    seuil_bonus_souple: float | None = Field(default=None, ge=0.0, le=1.0)
    plancher_malus: float | None = Field(default=None, ge=0.0, le=1.0)
    bonus_pct: float | None = Field(default=None, ge=0.0, le=5.0)
    bump_interval_factor_k: float | None = Field(default=None, ge=1.0, le=20.0)
    bump_y_min_days: int | None = Field(default=None, ge=7, le=730)
    bump_y_max_days: int | None = Field(default=None, ge=30, le=3650)
    bump_z_min: int | None = Field(default=None, ge=1, le=20)
    bump_x_ratio: float | None = Field(default=None, ge=0.1, le=1.0)
    bump_grace_ratio: float | None = Field(default=None, ge=0.1, le=2.0)


def _cfg_to_response(cfg) -> BonusConfigResponse:
    return BonusConfigResponse(
        seuil_malus_strict=cfg.seuil_malus_strict,
        seuil_malus_normal=cfg.seuil_malus_normal,
        seuil_malus_souple=cfg.seuil_malus_souple,
        seuil_bonus_strict=cfg.seuil_bonus_strict,
        seuil_bonus_normal=cfg.seuil_bonus_normal,
        seuil_bonus_souple=cfg.seuil_bonus_souple,
        plancher_malus=cfg.plancher_malus,
        bonus_pct=cfg.bonus_pct,
        bump_interval_factor_k=cfg.bump_interval_factor_k,
        bump_y_min_days=cfg.bump_y_min_days,
        bump_y_max_days=cfg.bump_y_max_days,
        bump_z_min=cfg.bump_z_min,
        bump_x_ratio=cfg.bump_x_ratio,
        bump_grace_ratio=cfg.bump_grace_ratio,
    )


@router.get("/bonus-config", response_model=BonusConfigResponse)
async def get_bonus_config(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Retourne la config bonus/malus du foyer courant."""
    foyer_uuid = current_foyer_uuid(current)
    cfg = await load_or_create_bonus_config(session, foyer_uuid)
    return _cfg_to_response(cfg)


@router.put("/bonus-config", response_model=BonusConfigResponse)
async def update_bonus_config(
    body: BonusConfigUpdate,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Met a jour la config bonus/malus du foyer courant (partiel)."""
    foyer_uuid = current_foyer_uuid(current)
    cfg = await load_or_create_bonus_config(session, foyer_uuid)

    # Applique uniquement les champs explicitement fournis.
    for field_name in body.model_fields_set:
        value = getattr(body, field_name)
        if value is not None:
            setattr(cfg, field_name, value)

    await session.commit()
    await session.refresh(cfg)
    return _cfg_to_response(cfg)
