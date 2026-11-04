"""Fair-share: target share of each member, status over 30 days, and the change history.

A target is a household-wide setting (one row per (household, member)). A member without
a row has the equal share, 1 / number of active members. Every reader of
`FairShareConfig.target_ratio` goes through `load_targets` so that the default is the
same everywhere (status, leaderboard).
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from uuid import UUID

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.completion import TaskCompletion
from app.models.fair_share import FairShareConfig, FairShareHistory
from app.models.task import Task
from app.models.user import User
from app.models.user_foyer import UserFoyer
from app.models.user_settings import UserSettings
from app.utils.errors import LocalizedHTTPException
from app.utils.scoping import active_member_ids, assert_active_members

# The client sends whole percentages / 100, so a valid total is 1.00 up to float noise.
# The tolerance only absorbs rounding, it is not a licence to leave 1 % unassigned.
TOTAL_TOLERANCE = 0.01
_EPSILON = 1e-9

STATUS_WINDOW_DAYS = 30
STATUS_MIN_COMPLETIONS = 10
DEFAULT_TOLERANCE_PERCENT = 30


def default_target(member_count: int) -> float:
    """Equal share for a household of `member_count` active members."""
    return 1.0 / max(member_count, 1)


async def load_targets(
    session: AsyncSession, foyer_uuid: UUID, member_ids
) -> dict[UUID, float]:
    """Target of each given member: the stored one, else the equal share.

    `member_ids` must be the household's ACTIVE members: their count is the denominator
    of the default.
    """
    members = list(member_ids)
    rows = await session.execute(
        select(FairShareConfig.user_id, FairShareConfig.target_ratio).where(
            FairShareConfig.foyer_id == foyer_uuid
        )
    )
    stored = {user_id: ratio for user_id, ratio in rows.all()}
    default = default_target(len(members))
    return {uid: stored.get(uid, default) for uid in members}


async def compute_status(session: AsyncSession, foyer_uuid: UUID) -> list[dict]:
    """Target vs actual share over the last 30 days, for each active member."""
    cutoff = datetime.now(timezone.utc) - timedelta(days=STATUS_WINDOW_DAYS)

    # Points and completion count per user over 30 days (household scope through tasks).
    completions_result = await session.execute(
        select(
            TaskCompletion.completed_by,
            func.sum(TaskCompletion.points_earned),
            func.count(TaskCompletion.id),
        )
        .join(Task, Task.id == TaskCompletion.task_id)
        .where(
            TaskCompletion.completed_at >= cutoff,
            TaskCompletion.deleted_at.is_(None),
            Task.foyer_id == foyer_uuid,
        ).group_by(TaskCompletion.completed_by)
    )
    user_points: dict[UUID, int] = {}
    total_completions = 0
    for user_id, points, count in completions_result.all():
        user_points[user_id] = points or 0
        total_completions += count or 0
    total_points = sum(user_points.values()) or 1
    has_enough_data = total_completions >= STATUS_MIN_COMPLETIONS

    # The tolerance is a personal display setting, not a household one.
    tol_result = await session.execute(
        select(UserSettings.user_id, UserSettings.fair_share_tolerance_percent)
    )
    tolerances = {row[0]: row[1] for row in tol_result.all()}

    users_result = await session.execute(
        select(User)
        .join(UserFoyer, UserFoyer.user_id == User.id)
        .where(UserFoyer.foyer_id == foyer_uuid, UserFoyer.left_at.is_(None))
    )
    users = users_result.scalars().all()
    targets = await load_targets(session, foyer_uuid, [u.id for u in users])

    statuses = []
    for user in users:
        target = targets[user.id]
        actual = user_points.get(user.id, 0) / total_points
        tol = tolerances.get(user.id, DEFAULT_TOLERANCE_PERCENT) / 100.0
        statuses.append({
            "user_id": user.id,
            "display_name": user.display_name,
            "target_ratio": target,
            "actual_ratio": actual,
            "above_target": has_enough_data and actual > target * (1.0 + tol),
        })
    return statuses


async def apply_targets(
    session: AsyncSession, foyer_uuid: UUID, author_id: UUID, items: list[tuple[UUID, float]]
) -> None:
    """Validate and store a full set of targets, and journal the change.

    Order of the checks matters: a user outside the household is refused first (400, the
    isolation guard), then the set of members, then the total. Does not commit.
    """
    sent_ids = [user_id for user_id, _ in items]
    await assert_active_members(session, foyer_uuid, sent_ids)
    members = await active_member_ids(session, foyer_uuid)
    if len(set(sent_ids)) != len(sent_ids) or set(sent_ids) != members:
        raise LocalizedHTTPException(status_code=422, error_code="FAIR_SHARE_MEMBERS_MISMATCH")
    total = sum(ratio for _, ratio in items)
    if abs(total - 1.0) > TOTAL_TOLERANCE + _EPSILON:
        raise LocalizedHTTPException(
            status_code=422,
            error_code="FAIR_SHARE_TOTAL_INVALID",
            format_args={"total": round(total * 100)},
        )

    before = await load_targets(session, foyer_uuid, members)
    existing = (await session.execute(
        select(FairShareConfig).where(FairShareConfig.foyer_id == foyer_uuid)
    )).scalars().all()
    by_user = {row.user_id: row for row in existing}
    for user_id, ratio in items:
        row = by_user.get(user_id)
        if row is not None:
            row.target_ratio = ratio
        else:
            session.add(FairShareConfig(user_id=user_id, foyer_id=foyer_uuid, target_ratio=ratio))
    session.add(FairShareHistory(
        foyer_id=foyer_uuid,
        changed_by=author_id,
        before={str(uid): ratio for uid, ratio in before.items()},
        after={str(uid): ratio for uid, ratio in items},
    ))


async def load_history(session: AsyncSession, foyer_uuid: UUID, limit: int) -> list[dict]:
    """Changes of the household's targets, most recent first.

    Names are read now from `users`: a member who left keeps their name until their
    account is anonymized, then reads as the anonymous name (departure policy).
    """
    rows = (await session.execute(
        select(FairShareHistory)
        .where(FairShareHistory.foyer_id == foyer_uuid)
        .order_by(FairShareHistory.changed_at.desc(), FairShareHistory.id)
        .limit(limit)
    )).scalars().all()
    wanted: set[UUID] = set()
    for row in rows:
        wanted.add(row.changed_by)
        wanted.update(UUID(uid) for uid in row.before)
        wanted.update(UUID(uid) for uid in row.after)
    names: dict[UUID, str] = {}
    if wanted:
        found = await session.execute(
            select(User.id, User.display_name).where(User.id.in_(wanted))
        )
        names = {user_id: name for user_id, name in found.all()}

    history = []
    for row in rows:
        shares = []
        for uid in sorted(set(row.before) | set(row.after), key=lambda u: names.get(UUID(u), "")):
            shares.append({
                "user_id": UUID(uid),
                "display_name": names.get(UUID(uid), "?"),
                "before": row.before.get(uid),
                "after": row.after.get(uid),
            })
        history.append({
            "id": row.id,
            "changed_at": row.changed_at,
            "changed_by": row.changed_by,
            "changed_by_name": names.get(row.changed_by, "?"),
            "shares": shares,
        })
    return history
