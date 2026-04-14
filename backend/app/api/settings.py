"""Routes reglages utilisateur."""

from __future__ import annotations

from uuid import UUID

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.models.user_foyer import UserFoyer
from app.models.user_settings import UserSettings
from app.utils.changelog import record_change
from app.utils.scoping import current_foyer_uuid

router = APIRouter(prefix="/api/settings", tags=["settings"])


class SettingsResponse(BaseModel):
    notif_daily: bool
    notif_urgent: bool
    notif_completed: bool
    notif_hour: int
    affinity_popup_enabled: bool
    affinity_expiry_months: int
    freshness_threshold: int
    fair_share_tolerance_percent: int
    home_ssids: list[str]


class SettingsUpdate(BaseModel):
    notif_daily: bool | None = None
    notif_urgent: bool | None = None
    notif_completed: bool | None = None
    notif_hour: int | None = Field(default=None, ge=0, le=23)
    affinity_popup_enabled: bool | None = None
    affinity_expiry_months: int | None = Field(default=None, ge=1, le=24)
    freshness_threshold: int | None = Field(default=None, ge=1, le=99)
    fair_share_tolerance_percent: int | None = Field(default=None, ge=0, le=100)
    home_ssids: list[str] | None = None


async def _load_or_create_user_foyer(
    session: AsyncSession, user_id: UUID, foyer_id: UUID
) -> UserFoyer:
    """Charge la ligne user_foyers ou leve si l'user n'est pas membre."""
    result = await session.execute(
        select(UserFoyer).where(
            UserFoyer.user_id == user_id,
            UserFoyer.foyer_id == foyer_id,
        )
    )
    uf = result.scalar_one_or_none()
    if uf is None:
        raise ValueError(f"User {user_id} not member of foyer {foyer_id}")
    return uf


def _ssids_to_list(raw: str | None) -> list[str]:
    if not raw:
        return []
    return [s for s in raw.split("|") if s]


@router.get("", response_model=SettingsResponse)
async def get_settings(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Recupere les reglages : partie per-user-global (user_settings) +
    partie per-foyer (user_foyers.home_ssids) pour le foyer actif.
    """
    user_id = UUID(current["user_id"])
    foyer_uuid = current_foyer_uuid(current)

    result = await session.execute(
        select(UserSettings).where(UserSettings.user_id == user_id)
    )
    settings = result.scalar_one_or_none()
    if not settings:
        settings = UserSettings(user_id=user_id)
        session.add(settings)
        await session.commit()
        await session.refresh(settings)

    # Home SSIDs : per-foyer depuis v0.7.2. On lit la row user_foyers.
    uf = await _load_or_create_user_foyer(session, user_id, foyer_uuid)

    return SettingsResponse(
        notif_daily=settings.notif_daily,
        notif_urgent=settings.notif_urgent,
        notif_completed=settings.notif_completed,
        notif_hour=settings.notif_hour,
        affinity_popup_enabled=settings.affinity_popup_enabled,
        affinity_expiry_months=settings.affinity_expiry_months,
        freshness_threshold=settings.freshness_threshold,
        fair_share_tolerance_percent=settings.fair_share_tolerance_percent,
        home_ssids=_ssids_to_list(uf.home_ssids),
    )


@router.put("", response_model=SettingsResponse)
async def update_settings(
    body: SettingsUpdate,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Met a jour les reglages. home_ssids est stocke par foyer (user_foyers),
    les autres champs restent per-user global (user_settings).
    """
    user_id = UUID(current["user_id"])
    foyer_uuid = current_foyer_uuid(current)

    result = await session.execute(
        select(UserSettings).where(UserSettings.user_id == user_id)
    )
    settings = result.scalar_one_or_none()
    if not settings:
        settings = UserSettings(user_id=user_id)
        session.add(settings)
        await session.flush()

    updates = body.model_dump(exclude_unset=True)

    # Home SSIDs : per-foyer. On update la row user_foyers separement.
    home_ssids_new = updates.pop("home_ssids", None)
    if home_ssids_new is not None:
        uf = await _load_or_create_user_foyer(session, user_id, foyer_uuid)
        uf.home_ssids = "|".join(home_ssids_new)

    for key, val in updates.items():
        setattr(settings, key, val)

    await record_change(
        session, "user_settings", settings.user_id, "UPDATE", current["user_id"],
        foyer_id=current["foyer_id"],
        new_data=body.model_dump(mode="json", exclude_unset=True),
    )
    await session.commit()
    await session.refresh(settings)

    # Re-read home_ssids depuis user_foyers pour la reponse
    uf = await _load_or_create_user_foyer(session, user_id, foyer_uuid)

    return SettingsResponse(
        notif_daily=settings.notif_daily,
        notif_urgent=settings.notif_urgent,
        notif_completed=settings.notif_completed,
        notif_hour=settings.notif_hour,
        affinity_popup_enabled=settings.affinity_popup_enabled,
        affinity_expiry_months=settings.affinity_expiry_months,
        freshness_threshold=settings.freshness_threshold,
        fair_share_tolerance_percent=settings.fair_share_tolerance_percent,
        home_ssids=_ssids_to_list(uf.home_ssids),
    )
