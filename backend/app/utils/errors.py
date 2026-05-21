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
