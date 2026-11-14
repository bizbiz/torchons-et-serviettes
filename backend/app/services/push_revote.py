"""Re-vote notifications: the request to the target, the answer to the requester.

Transport (FCM, pruning, token lookup) stays in app/services/push.py.
"""

from __future__ import annotations

from uuid import UUID

import firebase_admin
import structlog
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.task import Task
from app.models.user import User
from app.services.push import _send_to_users
from app.utils.scoping import hidden_among

logger = structlog.get_logger()

# --- Re-vote requests --------------------------------------------------------------------
#
# POST /prompts/request-revote used to send nothing: the target only learned of the request
# when the app next fetched its prompt queue. It now notifies the target AT ONCE, one
# notification per request: Quentin, 2026-11-11, « une demande de revote doit être
# immédiate ». A v0.44.1 build grouped them behind SYNC_QUIET_WINDOW; that wait was refused.
# No settings toggle: it is a direct request from a member.

_REVOTE_KIND_TEXT = {"frequency": "la fréquence", "effort": "la pénibilité"}


def build_revote_body(requester_name: str, kind: str, title: str, contexts: list[str] = ()) -> str:
    """« Q te demande de revoter la fréquence de X (Salon, Ménage) ».

    The contexts tell apart two tasks of the same title, « Passer l'aspirateur » in two rooms
    (Quentin, 2026-11-11); none, no parentheses.
    """
    where = f" ({', '.join(contexts)})" if contexts else ""
    return f"{requester_name} te demande de revoter {_REVOTE_KIND_TEXT.get(kind, 'le vote')} de {title}{where}"


async def _context_names(session: AsyncSession, task_id: UUID) -> list[str]:
    """The task's live contexts, main one first."""
    from app.models.context import Context, TaskContext

    rows = await session.execute(
        select(Context.name)
        .join(TaskContext, TaskContext.context_id == Context.id)
        .where(TaskContext.task_id == task_id, Context.deleted_at.is_(None))
        .order_by(TaskContext.position)
    )
    return [n for (n,) in rows.all()]


async def send_revote_request(
    session: AsyncSession, requester_id: UUID, target_id: UUID, task_id: UUID, title: str, kind: str,
) -> bool:
    """Notify the target of one re-vote request, now. Never raises.

    True when FCM accepted the request for at least one of the target's devices.
    """
    try:
        if not firebase_admin._apps:
            logger.info("push_skipped", kind="revote", reason="firebase_not_initialized")
            return False
        if str(task_id) in await hidden_among(session, target_id, {UUID(str(task_id))}):
            logger.info("push_skipped", kind="revote", reason="no_visible_task")
            return False
        name = (
            await session.execute(select(User.display_name).where(User.id == requester_id))
        ).scalar() or "?"
        body = build_revote_body(name, kind, title, await _context_names(session, UUID(str(task_id))))
        accepted = await _send_to_users(session, "revote", [(target_id, "Demande de revote", body)])
        return accepted > 0
    except Exception as e:
        logger.warning("push_failed", kind="revote", error=type(e).__name__)
        return False


# --- Re-vote answers ----------------------------------------------------------------------
#
# The requester used to hear nothing when the target answered or closed the request. The
# resolve route now tells them at once (2026-11-14). The data payload carries the old and new
# vote RAW so the app, when its re-vote dialog is open, shows its own text instead of the
# notification; the notification text serves every other case (app closed or in background).
# A dismissal notifies too (product owner), and an unchanged vote never reads « de 15 à 15 ».

_MONTHS = [str(m) for m in range(1, 13)]


def vote_to_wire(kind: str, value) -> str:
    """A vote as a data-payload string: effort points, or the 12 monthly day counts joined by
    commas; empty when there is no vote."""
    if value is None:
        return ""
    if kind == "effort":
        return str(int(value))
    return ",".join(str(int(value[m])) for m in _MONTHS)


def _uniform_days(value: dict) -> int | None:
    days = {int(v) for v in value.values()}
    return days.pop() if len(days) == 1 else None


def format_days_short(days: int) -> str:
    """Same wording as the app (DurationFormat.formatDaysHuman, short): 5 j, 1 sem. 3 j, 3 mois 2 j."""
    if days < 7:
        return f"{days} j"
    if days < 14:
        rest = days - 7
        return "1 sem." + (f" {rest} j" if rest else "")
    if days < 30:
        weeks, rest = divmod(days, 7)
        return f"{weeks} sem." + (f" {rest} j" if rest else "")
    months, rest = divmod(days, 30)
    return f"{months} mois" + (f" {rest} j" if rest else "")


def _vote_text(kind: str, value, *, bare: bool) -> str:
    """A vote for a sentence. Effort: the minutes as the stats history shows them (« 15min »,
    « 1h30 »). Frequency: « tous les 7 j » as the app shows it, or the bare duration when
    `bare` (inside « de X à Y »)."""
    if kind == "effort":
        from app.api.stats import _format_minutes

        return _format_minutes(int(value))
    days = _uniform_days(value)
    if days is None:
        return "variable" if bare else "variable selon le mois"
    return format_days_short(days) if bare else f"tous les {format_days_short(days)}"


def build_revote_answer_body(
    responder_name: str, kind: str, title: str, contexts: list[str], resolution: str, old, new,
) -> str:
    """The notification text for the requester; `old` / `new` are the votes (None: no vote)."""
    if resolution == "dismissed":
        return f"{responder_name} a fermé ta demande de revote sans changer son vote ({title})."
    lead = f"{responder_name} a répondu à ta demande de revote"
    where = f" ({', '.join(contexts)})" if contexts else ""
    what = _REVOTE_KIND_TEXT.get(kind, "le vote")
    if new is None:  # answered, but no vote could be read (request made before the snapshot)
        return f"{lead} ({title})."
    if old is not None and vote_to_wire(kind, old) == vote_to_wire(kind, new):
        return f"{lead} : vote confirmé, {_vote_text(kind, new, bare=False)} pour {title}{where}."
    if old is None:
        return f"{lead} : {what} de {title}{where}, fixée à {_vote_text(kind, new, bare=True)}".rstrip(".") + "."
    if kind == "effort" and int(old) < 12 and int(new) < 12:  # « de 15 à 10 min »
        span = f"de {int(old) * 5} à {int(new) * 5} min"
    else:
        span = f"de {_vote_text(kind, old, bare=True)} à {_vote_text(kind, new, bare=True)}"
    return f"{lead} : {what} de {title}{where}, {span}".rstrip(".") + "."


async def send_revote_answer(
    session: AsyncSession, *, requester_id: UUID, responder_id: UUID, task_id: UUID,
    kind: str, resolution: str, old, new,
) -> None:
    """Tell the requester their re-vote request was answered ("answered") or closed
    ("dismissed"). One push, after the commit; never raises. Skipped when the task is hidden
    from the requester (solo task of the responder: the text carries its title)."""
    try:
        if not firebase_admin._apps:
            logger.info("push_skipped", kind="revote_answer", reason="firebase_not_initialized")
            return
        if str(task_id) in await hidden_among(session, requester_id, {task_id}):
            logger.info("push_skipped", kind="revote_answer", reason="no_visible_task")
            return
        name = (
            await session.execute(select(User.display_name).where(User.id == responder_id))
        ).scalar() or "?"
        title = (await session.execute(select(Task.title).where(Task.id == task_id))).scalar() or "?"
        body = build_revote_answer_body(
            name, kind, title, await _context_names(session, task_id), resolution, old, new,
        )
        data = {
            "type": "revote_answered",
            "task_id": str(task_id),
            "kind": kind,
            "resolution": resolution,
            "responder_user_id": str(responder_id),
            "responder_name": name,
            "old_value": vote_to_wire(kind, old),
            "new_value": vote_to_wire(kind, new),
        }
        await _send_to_users(
            session, "revote_answer", [(requester_id, "Revote : réponse reçue", body)], data=data,
        )
    except Exception as e:
        logger.warning("push_failed", kind="revote_answer", error=type(e).__name__)
