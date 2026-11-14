"""Service de notifications push Firebase Cloud Messaging."""

from __future__ import annotations

import warnings
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import UUID

import firebase_admin
import structlog
from firebase_admin import credentials, messaging
from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.notification import NotificationToken
from app.models.user import User
from app.models.task import Task
from app.models.user_foyer import UserFoyer
from app.utils.scoping import hidden_among
from app.models.user_settings import UserSettings
from app.utils.text import join_with_amp

logger = structlog.get_logger()

# Initialiser Firebase Admin SDK
_firebase_key = Path(__file__).parent.parent.parent / "firebase-key.json"
if _firebase_key.exists() and not firebase_admin._apps:
    cred = credentials.Certificate(str(_firebase_key))
    firebase_admin.initialize_app(cred)


def _multicast(targets: list[tuple[str, str]], **content) -> messaging.MulticastMessage:
    """A message to (kind, value) targets: FIDs through `fids`, legacy FCM tokens through the
    deprecated `tokens` (APKs before 2026-11-12, NotificationToken.kind)."""
    fids = [v for k, v in targets if k == "fid"]
    tokens = [v for k, v in targets if k != "fid"]
    with warnings.catch_warnings():  # `tokens` warns; it stays until no APK sends a token
        warnings.simplefilter("ignore", DeprecationWarning)
        return messaging.MulticastMessage(tokens=tokens or None, fids=fids or None, **content)


def _send_multicast(kind: str, message: messaging.MulticastMessage) -> tuple[list[str], int]:
    """Send and log the outcome: counts and error classes only, never titles or tokens.

    Until 2026-10-17 every failure was swallowed, so a notification that never left
    looked exactly like one that did. Returns the tokens FCM declared unregistered and the
    number of messages FCM accepted.
    """
    try:
        resp = messaging.send_each_for_multicast(message)
    except Exception as e:
        logger.warning("push_failed", kind=kind, error=type(e).__name__)
        return [], 0
    errors = sorted({type(r.exception).__name__ for r in resp.responses if r.exception})
    log = logger.warning if resp.failure_count else logger.info
    log("push_sent", kind=kind, ok=resp.success_count, failed=resp.failure_count, errors=errors)
    # Responses come back tokens first, then fids (messaging._get_messages_from_multicast).
    # UnregisteredError covers a dead FID as well as a dead token.
    sent = (message.tokens or []) + (message.fids or [])
    dead = [
        tok for tok, r in zip(sent, resp.responses)
        if isinstance(r.exception, messaging.UnregisteredError)
    ]
    return dead, resp.success_count


async def _send_and_prune(kind: str, message: messaging.MulticastMessage) -> int:
    """Send, then delete the tokens FCM answered `UnregisteredError` for.

    Such a token belongs to an uninstalled app or a reset phone: it is refused on
    every send, forever. Only that error prunes: any other one can be transient.
    The delete runs in its OWN session, so it never commits the caller's transaction.
    Returns the number of messages FCM accepted.
    """
    dead, accepted = _send_multicast(kind, message)
    if not dead:
        return accepted
    from app.database import async_session

    try:
        async with async_session() as prune:
            await prune.execute(
                delete(NotificationToken).where(NotificationToken.fcm_token.in_(dead))
            )
            await prune.commit()
        logger.info("push_pruned", kind=kind, tokens=len(dead))
    except Exception as e:
        logger.warning("push_prune_failed", kind=kind, error=type(e).__name__)
    return accepted


# --- Helper centralise pour construire titre + corps + bucket de pref ---

def _title_for_collective_size(n: int) -> str:
    """Titre depend uniquement de la taille du collectif."""
    if n <= 1:
        return "Tache completee !"
    if n == 2:
        return "Tache completee a deux !"
    return "Tache completee a plusieurs !"


def build_completion_notification(
    recipient_id: UUID,
    completer_id: UUID,
    participant_ids: list[UUID],
    names: dict[UUID, str],
    task_title: str,
) -> tuple[str, str, str]:
    """Construit (titre, body, bucket) pour un destinataire donne.

    `participant_ids` inclut le recorder. Si len <= 1 ou unique == completer,
    on traite comme solo. `bucket` est l'un de "solo", "with_me", "without_me"
    et indique quel flag de UserSettings doit etre lu pour autoriser l'envoi.
    """
    # Normalise : liste deduplee preservant l'ordre.
    seen = set()
    deduped: list[UUID] = []
    for p in participant_ids:
        if p not in seen:
            seen.add(p)
            deduped.append(p)
    n = len(deduped)
    is_collective = n >= 2

    completer_name = names.get(completer_id, "?")

    # Solo : pas de collectif (ou collectif a 1 participant = degenere).
    if not is_collective:
        body = f"{completer_name} a fait : {task_title}"
        return ("Tache completee !", body, "solo")

    title = _title_for_collective_size(n)

    if recipient_id in deduped:
        # Destinataire participe au collectif (mais n'a pas enregistre).
        if n == 2:
            body = f"{completer_name} a fait {task_title} avec toi"
        else:
            # Autres participants = participants \ {completer, recipient}
            others = [
                names.get(p, "?")
                for p in deduped
                if p != completer_id and p != recipient_id
            ]
            others_joined = ", ".join(others) if others else ""
            if others_joined:
                body = f"{completer_name}, {others_joined} & toi avez fait {task_title}"
            else:
                # Cas degenere : N>=3 mais participants = {completer, recipient}
                # (ne devrait pas arriver vu la dedup) — fallback raisonnable.
                body = f"{completer_name} a fait {task_title} avec toi"
        return (title, body, "with_me")

    # Destinataire hors collectif : enumeration 3eme personne.
    participant_names = [names.get(p, "?") for p in deduped]
    body = f"{join_with_amp(participant_names)} ont fait {task_title}"
    return (title, body, "without_me")


# --- Envoi des notifications ---

_BUCKET_FLAG = {
    "solo": "notif_solo",
    "with_me": "notif_collective_with_me",
    "without_me": "notif_collective_without_me",
}


async def send_completion_event(
    session: AsyncSession,
    *,
    completer_id: UUID,
    participant_ids: list[UUID],
    task: Task,
    foyer_id: UUID,
) -> None:
    """Envoie une (ou plusieurs) notif(s) push apres une completion.

    `participant_ids` : tous les participants au collectif, completer inclus.
    Pour un solo, passer `[completer_id]`. Le routage par bucket lit
    `user_settings.notif_solo / notif_collective_with_me / notif_collective_without_me`.
    Personnalisation per-recipient (le mot "toi" varie), donc on envoie
    1 message par destinataire pour le bucket "with_me" et un multicast pour
    les autres.
    """
    if not firebase_admin._apps:
        logger.info("push_skipped", kind="completion", reason="firebase_not_initialized")
        return

    # 1. Membres ACTIFS du foyer hors recorder = candidats destinataires. Sans
    # left_at, un ancien membre recevait encore les titres des taches faites.
    members_result = await session.execute(
        select(UserFoyer.user_id).where(
            UserFoyer.foyer_id == foyer_id, UserFoyer.left_at.is_(None),
        )
    )
    member_ids = [row[0] for row in members_result.all()]
    candidates = [m for m in member_ids if m != completer_id]
    # Nor the members a solo task is hidden from (app/utils/scoping.py): the text carries
    # its title. Until 2026-10-18 every member got « Lo a fait <title> ».
    candidates = [m for m in candidates if str(task.id) not in await hidden_among(session, m, {task.id})]
    if not candidates:
        return

    # 2. Settings de chaque destinataire (1 requete).
    settings_result = await session.execute(
        select(UserSettings).where(UserSettings.user_id.in_(candidates))
    )
    settings_by_user: dict[UUID, UserSettings] = {
        s.user_id: s for s in settings_result.scalars().all()
    }

    # 3. Display names de tous les users mentionnes (recorder + participants + candidats).
    mentioned: set[UUID] = set(participant_ids) | set(candidates) | {completer_id}
    names_result = await session.execute(
        select(User.id, User.display_name).where(User.id.in_(mentioned))
    )
    names: dict[UUID, str] = {row[0]: row[1] for row in names_result.all()}

    # 4. Construction des messages personnalises + filtrage par flag.
    allowed: list[tuple[UUID, str, str]] = []  # (user_id, title, body)
    for r in candidates:
        title, body, bucket = build_completion_notification(
            r, completer_id, participant_ids, names, task.title
        )
        s = settings_by_user.get(r)
        flag_name = _BUCKET_FLAG[bucket]
        if not (s is not None and getattr(s, flag_name, False)):
            continue
        allowed.append((r, title, body))

    if not allowed:
        logger.info("push_skipped", kind="completion", reason="recipients_muted",
                    candidates=len(candidates))
        return
    await _send_to_users(session, "completion", allowed)


async def _send_to_users(
    session: AsyncSession, kind: str, allowed: list[tuple[UUID, str, str]],
    data: dict[str, str] | None = None,
) -> int:
    """Send each (user_id, title, body) to every token of its user, with the optional `data`
    payload on every message. Returns the number of messages FCM accepted."""
    # 5. Tokens FCM des destinataires retenus.
    tokens_result = await session.execute(
        select(NotificationToken.user_id, NotificationToken.kind, NotificationToken.fcm_token)
        .where(NotificationToken.user_id.in_([r[0] for r in allowed]))
    )
    tokens_by_user: dict[UUID, list[tuple[str, str]]] = {}
    for uid, kind_, tok in tokens_result.all():
        tokens_by_user.setdefault(uid, []).append((kind_, tok))

    # 6. Coalescer par (titre, body) — le bucket without_me partage souvent
    # le meme body, donc 1 multicast suffit. Le bucket with_me personnalise
    # via "toi" donc chaque destinataire a son propre bucket.
    grouped: dict[tuple[str, str], list[tuple[str, str]]] = {}
    for uid, title, body in allowed:
        for tok in tokens_by_user.get(uid, []):
            grouped.setdefault((title, body), []).append(tok)

    if not grouped:
        logger.info("push_skipped", kind=kind, reason="no_token", recipients=len(allowed))
        return 0

    accepted = 0
    for (title, body), targets in grouped.items():
        message = _multicast(
            targets, notification=messaging.Notification(title=title, body=body), data=data,
        )
        accepted += await _send_and_prune(kind, message)
    return accepted


# --- Completions arriving through /sync -------------------------------------------------
#
# The app records a completion offline-first, through /sync, and until 2026-10-18 that path
# sent nothing. Every online tick on the phone is its own /sync call, so a burst of twelve ticks
# is twelve calls: nothing is sent at commit time any more. The completions are QUEUED per
# (household, recorder) and one notification per (recipient, kind, author) leaves once the
# group has been quiet for SYNC_QUIET_WINDOW, flushed by a scheduler job (app/main.py).
#
# The queue lives in memory. A restart inside the window drops the pending group (those
# completions are never announced), and several uvicorn workers would each hold their own
# queue and split a burst: the API runs ONE worker, keep it that way or move this to the DB.
# No age limit: whatever the completion date, it notifies; a date other than today is shown.

SYNC_QUIET_WINDOW = timedelta(minutes=5)  # tests shrink it (monkeypatch)
_SYNC_TITLES_SHOWN = 5
_DISPLAY_TZ = "Europe/Paris"

# (household, recorder) -> {"ids": completion ids, "last": time of the latest enqueue}
_pending: dict[tuple[UUID, UUID], dict] = {}


def _local_date(moment: datetime):
    try:
        from zoneinfo import ZoneInfo

        return moment.astimezone(ZoneInfo(_DISPLAY_TZ)).date()
    except Exception:  # tzdata missing: UTC is a fair fallback for a day label
        return moment.astimezone(timezone.utc).date()


def build_sync_completions_body(author_name: str, titles: list[str], noted_for_you: bool = False) -> str:
    """« Lo a fait : X », or « Lo a fait 3 tâches : X, Y, Z » (at most five titles, then «…»).

    `noted_for_you`: the text for the completer, « Lo a noté que tu as fait : X ».
    """
    lead = f"{author_name} a noté que tu as fait" if noted_for_you else f"{author_name} a fait"
    if len(titles) == 1:
        return f"{lead} : {titles[0]}"
    shown = ", ".join(titles[:_SYNC_TITLES_SHOWN])
    more = "…" if len(titles) > _SYNC_TITLES_SHOWN else ""
    return f"{lead} {len(titles)} tâches : {shown}{more}"


async def send_sync_completions(
    session: AsyncSession, completion_ids: list, recorder_id, now: datetime | None = None,
) -> None:
    """Queue the completions a /sync call has just inserted; nothing is sent here.

    Called after the commit: a failure is logged, never raised, the ops are applied.
    """
    if not completion_ids:
        return
    if not firebase_admin._apps:
        logger.info("push_skipped", kind="sync_completion", reason="firebase_not_initialized")
        return
    try:
        from app.models.completion import TaskCompletion

        recorder = UUID(str(recorder_id))
        rows = await session.execute(
            select(TaskCompletion.id, Task.foyer_id)
            .join(Task, Task.id == TaskCompletion.task_id)
            .where(TaskCompletion.id.in_(completion_ids))
        )
        stamp = now or datetime.now(timezone.utc)
        queued = 0
        for cid, foyer in rows.all():
            group = _pending.setdefault((foyer, recorder), {"ids": [], "last": stamp})
            group["ids"].append(cid)
            group["last"] = stamp
            queued += 1
        logger.info("push_queued", kind="sync_completion", completions=queued)
    except Exception as e:
        logger.warning("push_failed", kind="sync_completion", error=type(e).__name__)


async def flush_idle_completions(now: datetime | None = None) -> int:
    """Send every group quiet for SYNC_QUIET_WINDOW; returns the number of groups flushed."""
    from app.database import async_session

    now = now or datetime.now(timezone.utc)
    due = [k for k, g in _pending.items() if now - g["last"] >= SYNC_QUIET_WINDOW]
    for key in due:
        group = _pending.pop(key, None)
        if group is None:
            continue
        foyer, recorder = key
        try:
            async with async_session() as session:
                await _send_sync_completions(session, group["ids"], recorder, foyer)
        except Exception as e:
            logger.warning("push_failed", kind="sync_completion", error=type(e).__name__)
    return len(due)


def _label(title: str, completed_at: datetime, today) -> str:
    day = _local_date(completed_at)
    return title if day == today else f"{title} ({day:%d/%m})"


async def _group_context(session: AsyncSession, foyer_id: UUID, named: set[UUID]):
    """Active members, their settings by user id, and the display names of `named`."""
    members = await session.execute(
        select(UserFoyer.user_id).where(UserFoyer.foyer_id == foyer_id, UserFoyer.left_at.is_(None))
    )
    member_ids = [m for (m,) in members.all()]
    settings_rows = await session.execute(select(UserSettings).where(UserSettings.user_id.in_(member_ids)))
    names_rows = await session.execute(select(User.id, User.display_name).where(User.id.in_(named)))
    return member_ids, {s.user_id: s for s in settings_rows.scalars().all()}, dict(names_rows.all())


async def _send_sync_completions(
    session: AsyncSession, completion_ids: list, recorder_id: UUID, foyer_id: UUID,
) -> None:
    from app.models.completion import TaskCompletion

    rows = await session.execute(
        select(TaskCompletion.completed_by, Task.id, Task.title, TaskCompletion.completed_at)
        .join(Task, Task.id == TaskCompletion.task_id)
        .where(
            TaskCompletion.id.in_(completion_ids),
            TaskCompletion.deleted_at.is_(None),
            Task.deleted_at.is_(None),
            Task.foyer_id == foyer_id,
        )
        .order_by(TaskCompletion.completed_at)
    )
    today = _local_date(datetime.now(timezone.utc))
    # completer -> {(task id, day): (task id, label)}, in completion order.
    done: dict[UUID, dict] = {}
    for completer, task_id, title, at in rows.all():
        done.setdefault(completer, {})[(task_id, _local_date(at))] = (task_id, _label(title, at, today))

    member_ids, settings, names = await _group_context(session, foyer_id, set(done) | {recorder_id})

    async def visible_labels(recipient: UUID, tasks: dict) -> list[str]:
        # Each recipient hears only of the tasks they may see (app/utils/scoping.py).
        hidden = await hidden_among(session, recipient, {tid for tid, _ in tasks.values()})
        return [label for tid, label in tasks.values() if str(tid) not in hidden]

    allowed: list[tuple[UUID, str, str]] = []
    for completer, tasks in done.items():
        for recipient in member_ids:
            s = settings.get(recipient)
            if s is None or recipient == recorder_id:
                # The recorder knows what they ticked (and, ticking for themselves, did it).
                continue
            if recipient == completer:
                # Ticked in my name: « R a noté que tu as fait … ».
                if not s.notif_collective_with_me:
                    continue
                author, noted = names.get(recorder_id, "?"), True
            else:
                if not s.notif_solo:
                    continue
                author, noted = names.get(completer, "?"), False
            titles = await visible_labels(recipient, tasks)
            if titles:
                title = "Tâche complétée !" if len(titles) == 1 else "Tâches complétées !"
                allowed.append((recipient, title, build_sync_completions_body(author, titles, noted)))

    if not allowed:
        logger.info("push_skipped", kind="sync_completion", reason="no_recipient", groups=len(done))
        return
    await _send_to_users(session, "sync_completion", allowed)


async def send_data_message_to_users(
    session: AsyncSession,
    user_ids: list[UUID],
    data: dict[str, str],
) -> None:
    """Envoie un FCM data-only message aux users donnes (pas de notification visible).

    Utilise pour les events in-app live : propositions d'objectifs v0.8.1,
    etc. Les clients reçoivent le message via le handler onMessage et
    reagissent directement dans l'UI (popup, refresh, etc.).

    `data` doit contenir uniquement des strings (FCM data payload constraint).
    """
    if not firebase_admin._apps or not user_ids:
        return

    tokens_result = await session.execute(
        select(NotificationToken.kind, NotificationToken.fcm_token).where(
            NotificationToken.user_id.in_(user_ids)
        )
    )
    targets = [(row[0], row[1]) for row in tokens_result.all()]
    if not targets:
        return

    message = _multicast(targets, data=data)
    # Not critical: the polling fallback takes over. Logged so a failure is visible.
    await _send_and_prune("data", message)
