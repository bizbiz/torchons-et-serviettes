"""Server-side gate refusing API access to app versions that are too old.

Why the refusal lives on the SERVER and not only in the app
-----------------------------------------------------------
A blocking screen inside the APK is ergonomics: it tells a well-meaning user to
update. It is not a barrier -- the APK one wants to stop is exactly the one that
can be patched to skip the check. The barrier is here: the server declines to
serve data. (Even so, the client declares its own version, so it can lie. This
gate protects against a buggy build still in circulation among trusted users --
the real risk for this app -- not against an attacker who controls the APK. A
leaked-credential scenario is answered by revoking tokens, not by this.)

Two design rules that are not negotiable
----------------------------------------
1. **A request WITHOUT the header passes.** Every APK built before this feature
   existed sends nothing. Refusing them would brick every phone in the field the
   moment this is deployed -- before a fixed APK even exists. Once every build in
   circulation sends the header, `MIN_APP_VERSION_CODE` can be raised to fence
   them out by version number instead.
2. **The threshold is explicit, never derived from `APP_VERSION`.** That value is
   the first `## vX.Y.Z` line of a changelog copied by hand between machines; it
   has already lied in both directions (2026-08-20 and 2026-09-01, see
   `scripts/deployed_versions.sh`). A gate resting on it would inherit the lie,
   except this time the lie locks people out of the app.

`versionCode` is compared, never `versionName`: it is a monotonic integer, while
"0.9.0" sorts above "0.10.0" as a string.

Each channel already has its own backend (prod on Scaleway, beta on its own host), so
the threshold is per-deployment and no channel needs to be named in the header.

Turning a threshold ON is the risky part, and its steps are ordered for a
reason: `docs/version-gate.fr.md`.
"""

from __future__ import annotations

from fastapi import Request
from fastapi.responses import JSONResponse
from starlette.middleware.base import BaseHTTPMiddleware

# The client sends its BuildConfig.VERSION_CODE here.
VERSION_HEADER = "X-App-Version-Code"

# Paths that must answer even to a fenced-out client. Diagnosing a deployment
# with every route closed is what turns a bad threshold into an outage: /health
# is how both a human and the app itself find out what is being asked of them.
EXEMPT_PATHS = (
    "/api/health",
    "/docs",
    "/redoc",
    "/openapi.json",
)


def _is_exempt(path: str) -> bool:
    """Exempt a path only on a full SEGMENT boundary.

    A plain `path.startswith(EXEMPT_PATHS)` compares characters, not routes: it
    would also exempt "/api/healthcheck-data" -- a data route leaving the gate
    with no error and no warning, just an open door. Nothing is named that today,
    which is precisely why the mistake would go unnoticed until someone adds one.
    """
    return any(path == p or path.startswith(p + "/") for p in EXEMPT_PATHS)

# Stable code the client keys its localized message on. The gate must stay
# readable by an offline-first client, so the body carries a code plus raw
# numbers -- never a prose sentence the app would have to display verbatim.
ERROR_CODE = "app_update_required"


def _parse_version_code(raw: str | None) -> int | None:
    """Return the version code, or None when the header is absent or malformed.

    A malformed header is treated exactly like an absent one: refusing on a
    value we failed to parse would fence out clients for a reason that has
    nothing to do with their version.
    """
    if raw is None:
        return None
    try:
        return int(raw.strip())
    except (ValueError, AttributeError):
        return None


class VersionGateMiddleware(BaseHTTPMiddleware):
    """Refuse data routes with 426 when the client is below the threshold.

    426 Upgrade Required is not what RFC 7231 reserves it for (protocol
    upgrades), but it is the code the mobile ecosystem settled on for this
    exact case, and it is unambiguous for a client: nothing about the request
    can be retried or fixed, only the app itself.
    """

    def __init__(self, app, min_version_code: int = 0, download_url: str = "") -> None:
        super().__init__(app)
        self.min_version_code = min_version_code
        self.download_url = download_url

    async def dispatch(self, request: Request, call_next):
        # 0 disables the gate entirely -- the default, so that merely deploying
        # this code changes nothing until a threshold is set deliberately.
        if self.min_version_code <= 0:
            return await call_next(request)

        if _is_exempt(request.url.path):
            return await call_next(request)

        client_code = _parse_version_code(request.headers.get(VERSION_HEADER))
        if client_code is None or client_code >= self.min_version_code:
            return await call_next(request)

        return JSONResponse(
            status_code=426,
            content={
                # `detail` keeps the shape every other error of this API has,
                # so an older client that only knows how to read `detail` still
                # shows something rather than crashing on an unknown body.
                "detail": ERROR_CODE,
                "code": ERROR_CODE,
                "min_version_code": self.min_version_code,
                "your_version_code": client_code,
                "download_url": self.download_url,
            },
        )
