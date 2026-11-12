"""A member who leaves: 30 days to come back, then the account is anonymized.

Rule set by Quentin on 2026-11-02, the same for both ways of leaving: leaving one's last
household (POST /foyers/{id}/leave) and deleting one's account (DELETE /me).
- For RETURN_WINDOW_DAYS, "Retrouver mon foyer" with the recovery code brings the member back
  into the household they left: the account is undeleted, the membership active again, the
  household unarchived, their suspended solo tasks reactivated and their shared tasks
  given back (task_departure.py).
- After that, the nightly cleanup ANONYMIZES the account instead of deleting it. Deleting failed
  as soon as the account had acted (six foreign keys to users with no ON DELETE, measured on
  2026-11-02), and the completions must stay in the household history (Quentin's choice). The
  name becomes ANONYMOUS_NAME, the recovery code is erased (no return possible any more), the
  personal rows go, the solo tasks go.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

from sqlalchemy import and_, delete, exists, func, or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.device import Device
from app.models.fair_share import FairShareConfig
from app.models.foyer import Foyer
from app.models.notification import NotificationToken
from app.models.streak import Streak
from app.models.suggestion_dismissal import SuggestionDismissal
from app.models.user import User
from app.models.user_foyer import UserFoyer
from app.models.user_prompt import UserPrompt
from app.models.user_settings import UserSettings
from app.services.task_departure import (
    delete_tasks_only_assigned_to, reactivate_suspended_tasks, restore_shared_assignments,
)

RETURN_WINDOW_DAYS = 30
# Stored as is, so that APKs that do not know the `anonymized` flag still show something sane.
ANONYMOUS_NAME = "Ancien membre"

# Rows that only describe the person, deleted at anonymization. Everything that describes the
# household (completions, votes, efforts, change_log) stays, pointing to the anonymized row.
PERSONAL_TABLES = (
    (NotificationToken, NotificationToken.user_id),
    (Device, Device.user_id),
    (UserSettings, UserSettings.user_id),
    (Streak, Streak.user_id),
    (UserPrompt, UserPrompt.user_id),
    (FairShareConfig, FairShareConfig.user_id),
    (SuggestionDismissal, SuggestionDismissal.user_id),
)


def return_cutoff(now: datetime | None = None) -> datetime:
    """Departures at or after this instant can still be undone."""
    return (now or datetime.now(timezone.utc)) - timedelta(days=RETURN_WINDOW_DAYS)


def can_still_return(user: User, now: datetime | None = None) -> bool:
    if user.anonymized_at is not None:
        return False
    return user.deleted_at is None or user.deleted_at >= return_cutoff(now)


async def foyer_to_return_to(session: AsyncSession, user: User) -> uuid.UUID | None:
    """The household a restore lands in: the active one, else the last one left in the window."""
    if user.last_active_foyer_id is not None:
        return user.last_active_foyer_id
    row = await session.execute(
        select(UserFoyer.foyer_id)
        .join(Foyer, Foyer.id == UserFoyer.foyer_id)
        .where(
            UserFoyer.user_id == user.id,
            UserFoyer.left_at >= return_cutoff(),
            Foyer.deleted_at.is_(None),
        )
        .order_by(UserFoyer.left_at.desc())
        .limit(1)
    )
    return row.scalar_one_or_none()


async def bring_back(session: AsyncSession, user: User) -> uuid.UUID | None:
    """Undo a departure still in its window. Returns the household the user is now active in."""
    user.deleted_at = None
    foyer_id = await foyer_to_return_to(session, user)
    if foyer_id is None:
        return None
    membership = (await session.execute(select(UserFoyer).where(
        # Any left_at, on purpose: this is the departure being undone (tested just below).
        UserFoyer.user_id == user.id, UserFoyer.foyer_id == foyer_id,
    ))).scalar_one_or_none()
    if membership is None:
        return None
    left_at = membership.left_at
    if left_at is not None:
        membership.left_at = None
        if membership.role == "former_member":
            # Alone in the household again: they must be able to manage it.
            others = (await session.execute(select(func.count()).select_from(UserFoyer).where(
                UserFoyer.foyer_id == foyer_id, UserFoyer.left_at.is_(None),
                UserFoyer.user_id != user.id,
            ))).scalar_one()
            membership.role = "member" if others else "admin"
        foyer = await session.get(Foyer, foyer_id)
        if foyer is not None:
            foyer.archived_at = None
        await reactivate_suspended_tasks(session, user.id, foyer_id)
        await restore_shared_assignments(session, user.id, foyer_id, left_at)
    user.last_active_foyer_id = foyer_id
    return foyer_id


def _departed_for_good(cutoff: datetime):
    """Accounts whose return window is over, not yet anonymized."""
    active_somewhere = exists().where(UserFoyer.user_id == User.id, UserFoyer.left_at.is_(None))
    left_recently = exists().where(UserFoyer.user_id == User.id, UserFoyer.left_at >= cutoff)
    ever_member = exists().where(UserFoyer.user_id == User.id)
    left_last_household = and_(
        User.deleted_at.is_(None), ever_member, ~active_somewhere, ~left_recently,
    )
    deleted_long_ago = and_(User.deleted_at.is_not(None), User.deleted_at < cutoff)
    return and_(User.anonymized_at.is_(None), or_(deleted_long_ago, left_last_household))


async def anonymize_departed_users(session: AsyncSession, now: datetime | None = None) -> int:
    """Nightly: anonymize every account whose return window is over. Returns the count."""
    now = now or datetime.now(timezone.utc)
    users = (await session.execute(
        select(User).where(_departed_for_good(return_cutoff(now)))
    )).scalars().all()
    if not users:
        return 0
    ids = [u.id for u in users]
    await delete_tasks_only_assigned_to(session, ids)
    for model, column in PERSONAL_TABLES:
        await session.execute(delete(model).where(column.in_(ids)))
    for user in users:
        user.display_name = ANONYMOUS_NAME
        user.username = f"former_{user.id.hex[:12]}"
        user.avatar_url = None
        user.recovery_code_hash = None
        user.recovery_lookup = None
        user.recovery_code_last_used_at = None
        user.last_active_foyer_id = None
        user.deleted_at = user.deleted_at or now
        user.anonymized_at = now
    return len(users)
