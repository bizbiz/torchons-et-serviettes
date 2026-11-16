"""What anonymizing an account erases, table by table (privacy decision 4, 2026-11-15).

Called by member_departure.anonymize_departed_users, i.e. 30 days after a member deleted the
account or left the last household. The privacy policy copies this list, keep them in step.

- DELETED, rows that only describe the person: PERSONAL_TABLES below.
- EMPTIED: the notes of their completions, the Wi-Fi names (SSID) of their memberships, and
  their personal objective preferences there (daily_objective_minutes NULL, display unit 'min').
- PURGED: the change_log rows they wrote (see purge_change_log for the one exception).
- KEPT, pseudonymized: the users row (name ANONYMOUS_NAME, see member_departure), their
  completions (the household statistics), their effort and frequency votes, user_foyers (role,
  dates), their per-member module_states, fair_share_history, objectives
  assigned to them. The users row itself goes once no household references it any more
  (delete_unreferenced_anonymized_users, run after the household purge).
"""

from __future__ import annotations

import uuid

from sqlalchemy import and_, delete, exists, func, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.badge import Badge
from app.models.completion import TaskCompletion
from app.models.device import Device
from app.models.fair_share import FairShareConfig, FairShareHistory
from app.models.module_guide_view import ModuleGuideView
from app.models.notification import NotificationToken
from app.models.objective_proposition import ObjectiveProposition, ObjectivePropositionResponse
from app.models.streak import Streak
from app.models.suggestion_dismissal import SuggestionDismissal
from app.models.sync import ChangeLog
from app.models.task_affinity import TaskAffinity
from app.models.user import User
from app.models.user_foyer import UserFoyer
from app.models.user_prompt import UserPrompt
from app.models.user_settings import UserSettings

# Rows that only describe the person. Their objective proposals take the other members'
# answers to them along (ON DELETE CASCADE); their own answers to others' proposals are listed.
PERSONAL_TABLES = (
    (NotificationToken, NotificationToken.user_id),
    (Device, Device.user_id),
    (UserSettings, UserSettings.user_id),
    (Streak, Streak.user_id),
    (UserPrompt, UserPrompt.user_id),
    (FairShareConfig, FairShareConfig.user_id),
    (SuggestionDismissal, SuggestionDismissal.user_id),
    (TaskAffinity, TaskAffinity.user_id),
    (Badge, Badge.user_id),
    (ModuleGuideView, ModuleGuideView.user_id),
    (ObjectivePropositionResponse, ObjectivePropositionResponse.user_id),
    (ObjectiveProposition, ObjectiveProposition.proposer_id),
)

# Foreign keys to users with no ON DELETE (measured on the dev schema, 2026-11-16): a users row
# still named by one of them cannot be deleted, and trying would fail the whole nightly job.
_BLOCKING_REFERENCES = (
    (UserFoyer, UserFoyer.user_id),
    (TaskCompletion, TaskCompletion.completed_by),
    (FairShareHistory, FairShareHistory.changed_by),
    (ChangeLog, ChangeLog.changed_by),
    (NotificationToken, NotificationToken.user_id),
    (FairShareConfig, FairShareConfig.user_id),
    (Streak, Streak.user_id),
    (UserPrompt, UserPrompt.user_id),
)


async def erase_personal_data(session: AsyncSession, user_ids: list[uuid.UUID]) -> None:
    """Delete, empty and purge everything listed in the module docstring for these accounts."""
    for model, column in PERSONAL_TABLES:
        await session.execute(delete(model).where(column.in_(user_ids)))
    # Notes of their completions only: a note another member wrote on their own completion
    # stays (task_completions has no author column, a note lives on the completer's row).
    await session.execute(
        update(TaskCompletion)
        .where(TaskCompletion.completed_by.in_(user_ids), TaskCompletion.notes.is_not(None))
        .values(notes=None)
    )
    await session.execute(
        # Any left_at, on purpose: every membership of theirs, the former ones included.
        # Personal preferences go back to their neutral value: SSIDs empty, no daily objective
        # (NULL, nullable column), display unit at its server default 'min' (NOT NULL column).
        update(UserFoyer)
        .where(UserFoyer.user_id.in_(user_ids))
        .values(home_ssids="", daily_objective_minutes=None, objective_display_unit="min")
    )
    await purge_change_log(session, user_ids)


async def purge_change_log(session: AsyncSession, user_ids: list[uuid.UUID]) -> None:
    """Delete the change_log rows these accounts wrote, except the newest row of the table.

    server_version is MAX(server_version) + 1 (utils/changelog.py) and phones keep the highest
    version they were served: deleting the newest row would hand its number out again, and
    every phone already past it would silently skip that next change. That one row stays,
    unsigned (changed_by NULL); it is household data (a task change), purged with the household.
    """
    newest = (await session.execute(select(func.max(ChangeLog.server_version)))).scalar()
    if newest is None:
        return
    await session.execute(delete(ChangeLog).where(
        ChangeLog.changed_by.in_(user_ids), ChangeLog.server_version < newest,
    ))
    await session.execute(
        update(ChangeLog).where(ChangeLog.changed_by.in_(user_ids)).values(changed_by=None)
    )


async def delete_unreferenced_anonymized_users(session: AsyncSession) -> int:
    """Delete the anonymized users rows that nothing points to any more. Returns the count.

    In practice: the account's last household has been purged (cleanup.purge_old_foyers), which
    took its memberships, completions and fair-share history with it. While a household still
    holds their completions the row stays: deleting it would mean deleting those completions,
    i.e. the other members' statistics.
    """
    unreferenced = and_(*(~exists().where(column == User.id) for _, column in _BLOCKING_REFERENCES))
    result = await session.execute(
        delete(User)
        .where(User.anonymized_at.is_not(None), unreferenced)
        .execution_options(synchronize_session=False)
    )
    return result.rowcount or 0
