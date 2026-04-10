"""Service de notifications push Firebase Cloud Messaging."""

from __future__ import annotations

from pathlib import Path
from uuid import UUID

import firebase_admin
from firebase_admin import credentials, messaging
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.notification import NotificationToken
from app.models.task_assignment import TaskAssignment
from app.models.user import User

# Initialiser Firebase Admin SDK
_firebase_key = Path(__file__).parent.parent.parent / "firebase-key.json"
if _firebase_key.exists() and not firebase_admin._apps:
    cred = credentials.Certificate(str(_firebase_key))
    firebase_admin.initialize_app(cred)


async def send_task_completed_notification(
    session: AsyncSession,
    completed_by_id: str,
    task_id: str,
    task_title: str,
) -> None:
    """Envoie une notification push aux autres utilisateurs assignes a la tache."""
    if not firebase_admin._apps:
        return

    completed_by_uuid = UUID(completed_by_id)
    task_uuid = UUID(task_id)

    # Trouver le nom de l'utilisateur qui a complete
    user_result = await session.execute(
        select(User).where(User.id == completed_by_uuid)
    )
    user = user_result.scalar_one_or_none()
    if not user:
        return

    # Trouver les autres users assignes a cette tache (pas celui qui a complete)
    assigned_result = await session.execute(
        select(TaskAssignment.user_id).where(
            TaskAssignment.task_id == task_uuid,
            TaskAssignment.user_id != completed_by_uuid,
        )
    )
    other_user_ids = [row[0] for row in assigned_result.all()]
    if not other_user_ids:
        return

    # Trouver les tokens FCM de ces utilisateurs
    tokens_result = await session.execute(
        select(NotificationToken.fcm_token).where(
            NotificationToken.user_id.in_(other_user_ids)
        )
    )
    tokens = [row[0] for row in tokens_result.all()]
    if not tokens:
        return

    # Envoyer la notification
    message = messaging.MulticastMessage(
        notification=messaging.Notification(
            title="Tache completee !",
            body=f"{user.display_name} a fait : {task_title}",
        ),
        tokens=tokens,
    )

    try:
        messaging.send_each_for_multicast(message)
    except Exception:
        pass  # Silencieux — pas critique
