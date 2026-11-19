"""« À faire aujourd'hui » notifications: one grouped announcement, then reminders.

Spec: docs/plans/a-faire-aujourdhui.md § Notifications.

- Grouped: once an author has handed nothing more to a recipient for ANNOUNCE_QUIET_WINDOW,
  one notification « A a mis 3 tâches à faire aujourd'hui : X, Y et Z ». A delegation
  cancelled or settled inside the window is never announced. It cannot be muted on its own.
- Reminders at 12:00, 16:00 and 20:00 Europe/Paris: to each recipient with at least one task
  still handed to them, whose last notification of this feature (grouped or reminder) is more
  than REMINDER_GAP old, and who left `notif_today_reminders` on.

Unlike the completions of /sync (services/push.py, an in-memory queue), the state lives in the
database (`task_delegation_recipients.announced_at`, `user_settings.today_notified_at`): the
30-second scheduler job finds the due groups there, and a restart loses nothing.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from uuid import UUID

import firebase_admin
import structlog
from apscheduler.triggers.cron import CronTrigger
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.task import Task
from app.models.task_delegation import TaskDelegation, TaskDelegationRecipient
from app.models.user import User
from app.models.user_foyer import UserFoyer
from app.services import push
from app.services.delegations import is_active
from app.utils.scoping import hidden_among
from app.utils.user_settings import get_or_create_user_settings

logger = structlog.get_logger()

ANNOUNCE_QUIET_WINDOW = timedelta(minutes=5)
REMINDER_GAP = timedelta(hours=1)
REMINDER_HOURS = "12,16,20"
REMINDER_TZ = "Europe/Paris"
TITLE = "À faire aujourd'hui"
_TITLES_SHOWN = 5


def reminder_trigger() -> CronTrigger:
    """12:00, 16:00 and 20:00 Paris time, daylight saving included (the scheduler runs in UTC)."""
    return CronTrigger(hour=REMINDER_HOURS, minute=0, timezone=REMINDER_TZ)


def join_titles(titles: list[str]) -> str:
    """« X », « X et Y », « X, Y et Z »; past five titles, the first five then « … »."""
    if len(titles) > _TITLES_SHOWN:
        return ", ".join(titles[:_TITLES_SHOWN]) + "…"
    if len(titles) == 1:
        return titles[0]
    return ", ".join(titles[:-1]) + " et " + titles[-1]


def announcement_body(author_name: str, titles: list[str]) -> str:
    if len(titles) == 1:
        return f"{author_name} a mis une tâche à faire aujourd'hui : {titles[0]}"
    return f"{author_name} a mis {len(titles)} tâches à faire aujourd'hui : {join_titles(titles)}"


def reminder_body(titles: list[str]) -> str:
    if len(titles) == 1:
        return f"Il te reste une tâche à faire aujourd'hui : {titles[0]}"
    return f"Il te reste {len(titles)} tâches à faire aujourd'hui : {join_titles(titles)}"


def _live_delegations():
    """Active delegations of live tasks, recipients still members of the household."""
    member = select(UserFoyer.user_id).where(
        UserFoyer.foyer_id == TaskDelegation.foyer_id,
        UserFoyer.user_id == TaskDelegationRecipient.user_id,
        UserFoyer.left_at.is_(None),
    ).exists()
    return (
        select(TaskDelegationRecipient, TaskDelegation, Task.title)
        .join(TaskDelegation, TaskDelegation.id == TaskDelegationRecipient.delegation_id)
        .join(Task, Task.id == TaskDelegation.task_id)
        .where(
            *is_active(), member,
            Task.deleted_at.is_(None), Task.is_active.is_(True), Task.is_archived.is_(False),
        )
        .order_by(TaskDelegation.created_at)
    )


async def _visible_titles(session: AsyncSession, recipient: UUID, items: list) -> list[str]:
    """Titles of the (task id, title) pairs the recipient may see (app/utils/scoping.py)."""
    hidden = await hidden_among(session, recipient, {tid for tid, _ in items})
    return [title for tid, title in items if str(tid) not in hidden]


async def _names(session: AsyncSession, ids: set) -> dict:
    rows = await session.execute(select(User.id, User.display_name).where(User.id.in_(ids)))
    return dict(rows.all())


async def _send(session: AsyncSession, kind: str, allowed: list[tuple[UUID, str, str]]) -> None:
    if not allowed:
        return
    if not firebase_admin._apps:
        logger.info("push_skipped", kind=kind, reason="firebase_not_initialized")
        return
    await push._send_to_users(session, kind, allowed)


async def _due_announcements(session: AsyncSession, now: datetime) -> dict:
    """(foyer, author, recipient) -> recipient rows, for the groups quiet for the window."""
    rows = (
        await session.execute(_live_delegations().where(TaskDelegationRecipient.announced_at.is_(None)))
    ).all()
    groups: dict = {}
    for rec, deleg, title in rows:
        groups.setdefault((deleg.foyer_id, deleg.author_id, rec.user_id), []).append((rec, deleg, title))
    return {
        key: items for key, items in groups.items()
        if now - max(d.created_at for _, d, _ in items) >= ANNOUNCE_QUIET_WINDOW
    }


async def announce_due_delegations(session: AsyncSession, now: datetime | None = None) -> int:
    """Send the grouped notifications that are due; returns the number of groups handled."""
    now = now or datetime.now(timezone.utc)
    due = await _due_announcements(session, now)
    if not due:
        return 0
    names = await _names(session, {author for _, author, _ in due})
    allowed: list[tuple[UUID, str, str]] = []
    for (_foyer, author, recipient), items in due.items():
        for rec, _, _ in items:
            rec.announced_at = now  # hidden: dropped, never announced later
        titles = await _visible_titles(session, recipient, [(d.task_id, t) for _, d, t in items])
        if titles:
            allowed.append((recipient, TITLE, announcement_body(names.get(author, "?"), titles)))
            (await get_or_create_user_settings(session, recipient)).today_notified_at = now
    await session.commit()
    await _send(session, "today_announcement", allowed)
    return len(due)


async def send_today_reminders(session: AsyncSession, now: datetime | None = None) -> int:
    """Remind every recipient with tasks still handed to them; returns the number reminded."""
    now = now or datetime.now(timezone.utc)
    rows = (await session.execute(_live_delegations())).all()
    pending: dict = {}
    for rec, _deleg, title in rows:
        pending.setdefault(rec.user_id, {})[_deleg.task_id] = title
    allowed: list[tuple[UUID, str, str]] = []
    for recipient, tasks in pending.items():
        settings = await get_or_create_user_settings(session, recipient)
        recent = settings.today_notified_at and now - settings.today_notified_at <= REMINDER_GAP
        if not settings.notif_today_reminders or recent:
            continue
        titles = await _visible_titles(session, recipient, list(tasks.items()))
        if titles:
            allowed.append((recipient, TITLE, reminder_body(titles)))
            settings.today_notified_at = now
    await session.commit()
    await _send(session, "today_reminder", allowed)
    return len(allowed)


async def flush_today_announcements() -> None:
    """Scheduler job, every 30 s (app/main.py)."""
    from app.database import async_session

    try:
        async with async_session() as session:
            await announce_due_delegations(session)
    except Exception as e:
        logger.warning("push_failed", kind="today_announcement", error=type(e).__name__)


async def today_reminders_job() -> None:
    """Scheduler job at 12:00, 16:00 and 20:00 Paris time (app/main.py)."""
    from app.database import async_session

    try:
        async with async_session() as session:
            await send_today_reminders(session)
    except Exception as e:
        logger.warning("push_failed", kind="today_reminder", error=type(e).__name__)
