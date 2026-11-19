"""Short error identifiers on every error response.

Contract (shared with the Android client, do not rename):
- every response with status >= 400 carries the header `X-Error-Id: E-XXXXX`;
- a JSON object body also gets the key `"error_id"` with the same value;
- one log line per error: `error_id=... status=... method=... path=... user_id=... detail=...`
  (WARNING for 4xx, ERROR with the traceback for 5xx);
- an unhandled exception answers `{"detail": "internal_error", "error_id": ...}`, never the
  trace nor the exception message.

A pure ASGI middleware, placed outermost, is the one spot that sees all of them: the
HTTPException and validation handlers, `LocalizedHTTPException`, the `JSONResponse` the
version gate returns directly, and the exceptions that no handler caught.
"""

from __future__ import annotations

import json
import secrets
from typing import Any

import structlog
from jose import jwt

logger = structlog.get_logger()

ERROR_ID_HEADER = "X-Error-Id"
ERROR_ID_ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ"
_MAX_DETAIL = 200


def new_error_id() -> str:
    """Return `E-` plus 5 random characters from the unambiguous alphabet."""
    return "E-" + "".join(secrets.choice(ERROR_ID_ALPHABET) for _ in range(5))


def _user_id_from_scope(scope: dict) -> str:
    """Return the `sub` claim of a valid bearer token, or "-". The token is never logged."""
    from app.config import settings

    try:
        for name, value in scope.get("headers", []):
            if name == b"authorization":
                parts = value.decode("latin-1").split(" ", 1)
                if len(parts) == 2 and parts[0].lower() == "bearer":
                    claims = jwt.decode(parts[1].strip(), settings.jwt_secret, algorithms=["HS256"])
                    return str(claims.get("sub") or "-")
    except Exception:
        pass
    return "-"


def _short_detail(detail: Any) -> str:
    """One short line for the log. Validation errors keep location and type only: their
    `input` and `msg` fields can echo what the user typed."""
    if isinstance(detail, list):
        parts = []
        for item in detail[:5]:
            if isinstance(item, dict):
                loc = ".".join(str(p) for p in item.get("loc", []))
                parts.append(f"{loc}:{item.get('type', '?')}")
        text = "validation[" + ",".join(parts) + "]"
    else:
        text = str(detail)
    return " ".join(text.split())[:_MAX_DETAIL]


def _parse_json_object(body: bytes) -> dict | None:
    try:
        parsed = json.loads(body)
    except (ValueError, UnicodeDecodeError):
        return None
    return parsed if isinstance(parsed, dict) else None


class ErrorIdMiddleware:
    """Stamp every error response with an id, and log it once."""

    def __init__(self, app) -> None:
        self.app = app

    async def __call__(self, scope, receive, send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        state: dict[str, Any] = {"started": False, "held": None, "chunks": []}

        async def pass_through_or_hold(message) -> None:
            if message["type"] == "http.response.start":
                state["started"] = True
                if message["status"] >= 400:
                    state["held"] = message
                    return
                await send(message)
                return
            if state["held"] is None:
                await send(message)
                return
            # Error bodies are small JSON documents: buffer them to add the key.
            state["chunks"].append(message.get("body", b""))
            if not message.get("more_body", False):
                await self._flush_error(scope, state, send)

        try:
            await self.app(scope, receive, pass_through_or_hold)
        except Exception as exc:
            if state["started"]:
                raise
            error_id = new_error_id()
            logger.error(
                "request_error",
                error_id=error_id,
                status=500,
                method=scope["method"],
                path=scope["path"],
                user_id=_user_id_from_scope(scope),
                detail="internal_error",
                exc_info=exc,
            )
            await self._send(
                send, 500, error_id, {"detail": "internal_error", "error_id": error_id}, []
            )

    async def _flush_error(self, scope, state, send) -> None:
        start = state["held"]
        state["held"] = None
        status = start["status"]
        raw_headers = [(k, v) for k, v in start.get("headers", [])]
        body = b"".join(state["chunks"])
        error_id = new_error_id()
        parsed = _parse_json_object(body)
        detail: Any = "-"
        if parsed is not None:
            detail = parsed.get("detail", parsed.get("code", "-"))
            parsed["error_id"] = error_id
            body = json.dumps(parsed, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        log = logger.error if status >= 500 else logger.warning
        log(
            "request_error",
            error_id=error_id,
            status=status,
            method=scope["method"],
            path=scope["path"],
            user_id=_user_id_from_scope(scope),
            detail=_short_detail(detail),
        )
        await self._send(send, status, error_id, None, raw_headers, body)

    @staticmethod
    async def _send(send, status, error_id, content, raw_headers, body: bytes | None = None):
        if content is not None:
            body = json.dumps(content, separators=(",", ":")).encode("utf-8")
            raw_headers = [(b"content-type", b"application/json")]
        raw_headers = [
            (k, v)
            for k, v in raw_headers
            if k.lower() not in (b"content-length", ERROR_ID_HEADER.lower().encode())
        ]
        raw_headers.append((b"content-length", str(len(body)).encode()))
        raw_headers.append((ERROR_ID_HEADER.lower().encode(), error_id.encode()))
        await send({"type": "http.response.start", "status": status, "headers": raw_headers})
        await send({"type": "http.response.body", "body": body})
