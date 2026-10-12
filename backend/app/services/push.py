"""Service de notifications push Firebase Cloud Messaging."""

from __future__ import annotations

from pathlib import Path
from uuid import UUID

import firebase_admin
from firebase_admin import credentials, messaging
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.notification import NotificationToken
from app.models.user import User
from app.models.user_foyer import UserFoyer
from app.models.user_settings import UserSettings
from app.utils.text import join_with_amp

# Initialiser Firebase Admin SDK
_firebase_key = Path(__file__).parent.parent.parent / "firebase-key.json"
if _firebase_key.exists() and not firebase_admin._apps:
    cred = credentials.Certificate(str(_firebase_key))
    firebase_admin.initialize_app(cred)


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
    task_title: str,
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
            r, completer_id, participant_ids, names, task_title
        )
        s = settings_by_user.get(r)
        flag_name = _BUCKET_FLAG[bucket]
        if not (s is not None and getattr(s, flag_name, False)):
            continue
        allowed.append((r, title, body))

    if not allowed:
        return

    # 5. Tokens FCM des destinataires retenus.
    tokens_result = await session.execute(
        select(NotificationToken.user_id, NotificationToken.fcm_token)
        .where(NotificationToken.user_id.in_([r[0] for r in allowed]))
    )
    tokens_by_user: dict[UUID, list[str]] = {}
    for uid, tok in tokens_result.all():
        tokens_by_user.setdefault(uid, []).append(tok)

    # 6. Coalescer par (titre, body) — le bucket without_me partage souvent
    # le meme body, donc 1 multicast suffit. Le bucket with_me personnalise
    # via "toi" donc chaque destinataire a son propre bucket.
    grouped: dict[tuple[str, str], list[str]] = {}
    for uid, title, body in allowed:
        for tok in tokens_by_user.get(uid, []):
            grouped.setdefault((title, body), []).append(tok)

    for (title, body), tokens in grouped.items():
        if not tokens:
            continue
        message = messaging.MulticastMessage(
            notification=messaging.Notification(title=title, body=body),
            tokens=tokens,
        )
        try:
            messaging.send_each_for_multicast(message)
        except Exception:
            pass  # Silencieux — pas critique


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
        select(NotificationToken.fcm_token).where(
            NotificationToken.user_id.in_(user_ids)
        )
    )
    tokens = [row[0] for row in tokens_result.all()]
    if not tokens:
        return

    message = messaging.MulticastMessage(
        data=data,
        tokens=tokens,
    )
    try:
        messaging.send_each_for_multicast(message)
    except Exception:
        pass  # Silencieux — non critique, le polling fallback prendra le relai
