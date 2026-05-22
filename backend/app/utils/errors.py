"""Localisation server-side des erreurs HTTPException (E3.4 i18n).

Approche choisie :

- `LocalizedHTTPException` porte un `error_code` + un dict de
  `format_args` (pour interpoler des valeurs dynamiques dans le
  message).
- `ERROR_MESSAGES` : dict des messages par code et par locale. Les
  messages incluent du `{format}` Python standard pour les valeurs
  dynamiques.
- `register_localized_error_handler(app)` enregistre un handler
  FastAPI qui lit Accept-Language via `pick_locale_from_header`,
  formate le message dans la bonne locale, et renvoie une JSONResponse
  compatible avec le contrat existant (`{"detail": "<string>"}`).

Backward-compat : le format de réponse reste exactement le même que
`HTTPException(detail="...")` aujourd'hui — c'est une string dans le
champ `detail`. Les clients Android pré-E3.4 continuent à fonctionner
sans modification, ils afficheront juste la chaîne FR si Accept-Language
n'est pas envoyé (= fallback du backend) ou la chaîne EN si envoyé.

Migration progressive : tant qu'un site `HTTPException(...)` n'est pas
converti en `LocalizedHTTPException(...)`, il garde son comportement
actuel (detail FR statique). On migre prioritairement les sites
user-facing les plus visibles.
"""

from __future__ import annotations

from typing import Any

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse

from app.utils.locale import pick_locale_from_header


class LocalizedHTTPException(HTTPException):
    """HTTPException qui porte un error_code + format_args, pour que le
    handler global puisse construire le `detail` dans la bonne locale.

    Args:
        status_code: code HTTP comme d'habitude.
        error_code: clé stable dans ERROR_MESSAGES (ex. "PAIRING_EXPIRED").
        format_args: kwargs pour `.format()` sur le message (ex. {"elapsed_min": 12}).
        headers: passés tels quels à HTTPException.

    Le `detail` initial est fixé au message FR (fallback si le handler
    n'est pas appelé pour une raison ou une autre, p.ex. middleware qui
    intercepte avant).
    """

    def __init__(
        self,
        status_code: int,
        error_code: str,
        format_args: dict[str, Any] | None = None,
        headers: dict[str, str] | None = None,
    ) -> None:
        self.error_code = error_code
        self.format_args = format_args or {}
        # Fallback statique : message FR formaté. Sera potentiellement
        # ré-écrit par le handler en fonction de Accept-Language.
        fallback_msg = _format_message(error_code, "fr", self.format_args)
        super().__init__(status_code=status_code, detail=fallback_msg, headers=headers)


# Catalogue des messages d'erreur (FR + EN). Clé = error_code, valeur
# = dict locale -> template `str.format`. Garder les templates courts,
# parlants, sans terminologie technique pour les user-facing.
ERROR_MESSAGES: dict[str, dict[str, str]] = {
    # Pairing (QR code d'invitation)
    "PAIRING_TOKEN_INVALID": {
        "fr": "Code d'invitation invalide",
        "en": "Invalid invitation code",
    },
    "PAIRING_TOKEN_USED": {
        "fr": "Ce code d'invitation a deja ete utilise",
        "en": "This invitation code has already been used",
    },
    "PAIRING_TOKEN_EXPIRED": {
        "fr": "Ce code d'invitation a expire",
        "en": "This invitation code has expired",
    },
    "PAIRING_FOYER_GONE": {
        "fr": "Le foyer associe a ce code n'existe plus",
        "en": "The household associated with this code no longer exists",
    },
    "PAIRING_ACCOUNT_GONE": {
        "fr": "Le compte associe a ce code n'existe plus",
        "en": "The account associated with this code no longer exists",
    },
    "PAIRING_DISPLAY_NAME_REQUIRED": {
        "fr": "display_name est requis pour rejoindre un foyer",
        "en": "display_name is required to join a household",
    },
    # Recovery (code de récupération de foyer)
    "RECOVERY_TOKEN_INVALID": {
        "fr": "Token de restauration invalide ou expire",
        "en": "Invalid or expired recovery token",
    },
    "RECOVERY_CODE_NOT_FOUND": {
        "fr": "Code de recuperation introuvable",
        "en": "Recovery code not found",
    },
    # User / Foyer membership
    "USER_NOT_FOUND": {
        "fr": "Utilisateur introuvable",
        "en": "User not found",
    },
    "FOYER_NOT_FOUND": {
        "fr": "Foyer introuvable",
        "en": "Household not found",
    },
    "FOYER_NOT_MEMBER": {
        "fr": "Tu n'es pas membre de ce foyer",
        "en": "You're not a member of this household",
    },
    "FOYER_NOT_ACTIVE_MEMBER": {
        "fr": "Tu n'es pas membre actif de ce foyer",
        "en": "You're not an active member of this household",
    },
    "FOYER_ADMIN_REQUIRED": {
        "fr": "Seul un admin du foyer peut modifier ses reglages",
        "en": "Only a household admin can change these settings",
    },
    # Tasks / Completions
    "TASK_NOT_FOUND": {
        "fr": "Tache introuvable",
        "en": "Task not found",
    },
    "COMPLETION_NOT_FOUND": {
        "fr": "Completion introuvable",
        "en": "Completion not found",
    },
    "COLLECTIVE_NOT_FOUND": {
        "fr": "Collectif introuvable",
        "en": "Group event not found",
    },
    "COMPLETION_DUPLICATE": {
        "fr": "Tache deja completee il y a {elapsed_min} min. Prochain creneau possible a {retry_at}.",
        "en": "Task already completed {elapsed_min} min ago. Next slot available at {retry_at}.",
    },
    "COMPLETION_COLLECTIVE_DUPLICATE": {
        "fr": "Impossible de valider : un ou plusieurs participants ont deja valide cette tache recemment.",
        "en": "Cannot record: one or more participants have already validated this task recently.",
    },
    "COMPLETION_NO_PARTICIPANTS": {
        "fr": "Aucun participant fourni",
        "en": "No participants provided",
    },
    "COMPLETION_PARTICIPANTS_OUTSIDE_FOYER": {
        "fr": "Participants hors du foyer : {invalid}",
        "en": "Participants outside the household: {invalid}",
    },
    # ============================================================
    # Lot 3 i18n (post-launch) — reste des HTTPException backend.
    # ============================================================
    # Auth
    "AUTH_USER_UNKNOWN_FORMAT": {
        "fr": "Utilisateur inconnu: {username}",
        "en": "Unknown user: {username}",
    },
    "AUTH_USER_NOT_FOUND_FORMAT": {
        "fr": "Utilisateur '{username}' introuvable",
        "en": "User '{username}' not found",
    },
    # Categories / Rooms / Tasks / Completions / Objectives — generic "X not found"
    "CATEGORY_NOT_FOUND": {
        "fr": "Categorie introuvable",
        "en": "Category not found",
    },
    "ROOM_NOT_FOUND": {
        "fr": "Piece introuvable",
        "en": "Room not found",
    },
    "OBJECTIVE_NOT_FOUND": {
        "fr": "Objectif introuvable",
        "en": "Goal not found",
    },
    "OBJECTIVE_PERSONAL_REQUIRES_USER": {
        "fr": "assigned_to_user_id requis pour un objectif personnel",
        "en": "assigned_to_user_id is required for a personal goal",
    },
    "USER_FOYER_NOT_FOUND": {
        "fr": "User foyer introuvable",
        "en": "User-household link not found",
    },
    "CONTENT_FILE_NOT_FOUND_FORMAT": {
        "fr": "Fichier introuvable : {filename}",
        "en": "File not found: {filename}",
    },
    # Gamification / Votes
    "GAMIF_NOT_ASSIGNED_VOTE": {
        "fr": "Vous n'etes pas assigne a cette tache — vote non accepte.",
        "en": "You're not assigned to this task — vote rejected.",
    },
    "GAMIF_INVALID_FREQUENCY_FIELDS": {
        "fr": "Fournir exactement un des champs : every_n_days ou days_per_month.",
        "en": "Provide exactly one of: every_n_days or days_per_month.",
    },
    "VALIDATION_ERROR_FORMAT": {
        "fr": "{message}",
        "en": "{message}",
    },
    # Billing
    "BILLING_ADMIN_REQUIRED": {
        "fr": "Seul un admin du foyer peut activer le premium",
        "en": "Only a household admin can activate premium",
    },
    # Presence
    "PRESENCE_FROM_GT_TO": {
        "fr": "from doit etre <= to",
        "en": "from must be <= to",
    },
    "PRESENCE_RANGE_TOO_LARGE_FORMAT": {
        "fr": "Plage trop large (max {max_days} jours)",
        "en": "Range too large (max {max_days} days)",
    },
    "PRESENCE_INVALID_STATUS_FORMAT": {
        "fr": "status invalide: {status}",
        "en": "Invalid status: {status}",
    },
    # Prompts
    "PROMPT_NOT_FOUND": {
        "fr": "Prompt introuvable",
        "en": "Prompt not found",
    },
    "PROMPT_INVALID_RESOLUTION": {
        "fr": "resolution must be DISMISSED or ANSWERED",
        "en": "resolution must be DISMISSED or ANSWERED",
    },
    "PROMPT_INVALID_KIND": {
        "fr": "kind must be 'frequency' or 'effort'",
        "en": "kind must be 'frequency' or 'effort'",
    },
    "PROMPT_OWN_VOTE": {
        "fr": "Tu peux modifier ton propre vote directement",
        "en": "You can edit your own vote directly",
    },
    "FOYER_MEMBER_NOT_FOUND": {
        "fr": "Membre introuvable dans le foyer",
        "en": "Member not found in household",
    },
    # Propositions
    "PROPOSITION_NOT_FOUND": {
        "fr": "Proposition introuvable",
        "en": "Proposal not found",
    },
    "PROPOSITION_MISSING_SLIDERS_FORMAT": {
        "fr": "slider_values manque des users du foyer : {missing}",
        "en": "slider_values is missing some household users: {missing}",
    },
    "PROPOSITION_EXPIRED": {
        "fr": "Proposition expiree",
        "en": "Proposal expired",
    },
    "PROPOSITION_ALREADY_FORMAT": {
        "fr": "Proposition deja {status}",
        "en": "Proposal already {status}",
    },
    "PROPOSITION_IS_PROPOSER": {
        "fr": "Tu es le proposer",
        "en": "You're the proposer",
    },
    "PROPOSITION_ALREADY_RESPONDED": {
        "fr": "Deja repondu",
        "en": "Already answered",
    },
    "PROPOSITION_ONLY_PROPOSER_CANCELS": {
        "fr": "Seul le proposer peut annuler",
        "en": "Only the proposer can cancel",
    },
    # Sync
    "SYNC_UNKNOWN_TABLE_FORMAT": {
        "fr": "Table inconnue: {table}",
        "en": "Unknown table: {table}",
    },
    "SYNC_UNKNOWN_OPERATION_FORMAT": {
        "fr": "Operation inconnue: {operation}",
        "en": "Unknown operation: {operation}",
    },
    "SYNC_UNSUPPORTED_OPERATION_FORMAT": {
        "fr": "Operation non supportee pour {scope}: {operation}",
        "en": "Unsupported operation for {scope}: {operation}",
    },
}


def _format_message(
    error_code: str, locale: str, format_args: dict[str, Any]
) -> str:
    """Retourne le message formate pour `error_code` dans `locale`,
    avec fallback fr puis fallback "<error_code>" si la clé est absente.
    """
    entry = ERROR_MESSAGES.get(error_code)
    if entry is None:
        # Pas dans le catalogue : renvoyer le code brut. Utile pour debug
        # mais aussi pour le client qui peut le mapper s'il veut.
        return error_code
    template = entry.get(locale) or entry.get("fr") or next(iter(entry.values()), error_code)
    try:
        return template.format(**format_args)
    except (KeyError, IndexError):
        # Si les format_args ne matchent pas (bug), on renvoie le
        # template brut plutôt que de crasher.
        return template


def register_localized_error_handler(app: FastAPI) -> None:
    """Enregistre le handler global pour LocalizedHTTPException sur l'app.

    Le handler lit Accept-Language depuis la requête, formate le message
    dans la bonne locale, et renvoie une JSONResponse
    `{"detail": "<string>"}` — exactement le format que FastAPI utilise
    pour HTTPException standard, donc 100 % backward-compatible côté
    client.
    """

    @app.exception_handler(LocalizedHTTPException)
    async def _handler(request: Request, exc: LocalizedHTTPException) -> JSONResponse:
        accept_language = request.headers.get("accept-language")
        locale = pick_locale_from_header(accept_language)
        message = _format_message(exc.error_code, locale, exc.format_args)
        return JSONResponse(
            status_code=exc.status_code,
            content={"detail": message},
            headers=exc.headers,
        )
