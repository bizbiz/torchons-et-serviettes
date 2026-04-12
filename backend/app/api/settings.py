"""Routes reglages utilisateur."""

from __future__ import annotations

from uuid import UUID

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.models.user_settings import UserSettings
from app.utils.changelog import record_change

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


@router.get("", response_model=SettingsResponse)
async def get_settings(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Recupere les reglages de l'utilisateur courant."""
    user_id = UUID(current["user_id"])
    result = await session.execute(
        select(UserSettings).where(UserSettings.user_id == user_id)
    )
    settings = result.scalar_one_or_none()
    if not settings:
        # Creer les settings par defaut
        settings = UserSettings(user_id=user_id)
        session.add(settings)
        await session.commit()
        await session.refresh(settings)

    return SettingsResponse(
        notif_daily=settings.notif_daily,
        notif_urgent=settings.notif_urgent,
        notif_completed=settings.notif_completed,
        notif_hour=settings.notif_hour,
        affinity_popup_enabled=settings.affinity_popup_enabled,
        affinity_expiry_months=settings.affinity_expiry_months,
        freshness_threshold=settings.freshness_threshold,
        fair_share_tolerance_percent=settings.fair_share_tolerance_percent,
        home_ssids=[s for s in settings.home_ssids.split("|") if s] if settings.home_ssids else [],
    )


@router.put("", response_model=SettingsResponse)
async def update_settings(
    body: SettingsUpdate,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    """Met a jour les reglages de l'utilisateur courant."""
    user_id = UUID(current["user_id"])
    result = await session.execute(
        select(UserSettings).where(UserSettings.user_id == user_id)
    )
    settings = result.scalar_one_or_none()
    if not settings:
        settings = UserSettings(user_id=user_id)
        session.add(settings)
        await session.flush()

    updates = body.model_dump(exclude_unset=True)
    if "home_ssids" in updates:
        updates["home_ssids"] = "|".join(updates["home_ssids"])

    for key, val in updates.items():
        setattr(settings, key, val)

    await record_change(
        session, "user_settings", settings.user_id, "UPDATE", current["user_id"],
        new_data=body.model_dump(mode="json", exclude_unset=True),
    )
    await session.commit()
    await session.refresh(settings)

    return SettingsResponse(
        notif_daily=settings.notif_daily,
        notif_urgent=settings.notif_urgent,
        notif_completed=settings.notif_completed,
        notif_hour=settings.notif_hour,
        affinity_popup_enabled=settings.affinity_popup_enabled,
        affinity_expiry_months=settings.affinity_expiry_months,
        freshness_threshold=settings.freshness_threshold,
        fair_share_tolerance_percent=settings.fair_share_tolerance_percent,
        home_ssids=[s for s in settings.home_ssids.split("|") if s] if settings.home_ssids else [],
    )
