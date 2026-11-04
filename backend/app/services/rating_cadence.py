"""How often the automatic "rate this task" pop-up may appear, per member.

Each member carries a gap in days. An automatic pop-up that is dismissed without a
rating widens it (the member is not interested right now); one that gets a rating
narrows it. After every outcome the next automatic pop-up waits `gap` days.
Manual ratings and chained ratings after the first never reach this module: the app
reports exactly one outcome per automatic pop-up.
"""

from __future__ import annotations

from datetime import datetime, timedelta

from app.models.user_settings import UserSettings

MIN_GAP_DAYS = 1
MAX_GAP_DAYS = 10


def is_due(settings: UserSettings, now: datetime) -> bool:
    """True when an automatic pop-up may be shown now (never scheduled, or time is up)."""
    next_at = settings.rating_prompt_next_at
    return next_at is None or now >= next_at


def apply_outcome(settings: UserSettings, answered: bool, now: datetime) -> None:
    """Record the outcome of one automatic pop-up on the settings row (not committed)."""
    gap = settings.rating_prompt_gap_days
    gap = gap - 1 if answered else gap + 1
    gap = max(MIN_GAP_DAYS, min(MAX_GAP_DAYS, gap))
    settings.rating_prompt_gap_days = gap
    settings.rating_prompt_next_at = now + timedelta(days=gap)
