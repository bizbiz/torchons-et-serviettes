"""Per-IP rate limiting for the routes that need no authentication.

Why in the app and not in Caddy: the production image (`caddy:2-alpine`) ships no
`rate_limit` module, and a custom Caddy build is one more thing to maintain for one
directive. Why in memory: the API runs a single uvicorn worker
(`docker-entrypoint.sh`), so one process sees every request. A second worker, or a
second replica, would split the counters and multiply the allowance by their number.

The client IP is `request.client.host`. Behind Caddy that is Caddy's address unless
uvicorn trusts the forwarded headers: production sets FORWARDED_ALLOW_IPS=*, safe
there because the API is only reachable through Caddy (`expose`, no `ports`).
Without it, every client would share one counter.

What it protects, measured on 2026-10-12: a wrong recovery code costs one argon2
verify per account holding a code (61 ms each, 16 accounts in production), and
`/onboarding/create-foyer` creates a user, a household and 360 rows per call.
"""

from __future__ import annotations

import time
from collections import defaultdict, deque

from fastapi import Request

from app.utils.errors import LocalizedHTTPException

# bucket -> ip -> timestamps of the accepted calls inside the window
_calls: dict[str, dict[str, deque[float]]] = defaultdict(lambda: defaultdict(deque))


def rate_limited(bucket: str, max_calls: int, per_seconds: float):
    """FastAPI dependency: at most `max_calls` per IP per `per_seconds` on this bucket."""

    async def dependency(request: Request) -> None:
        ip = request.client.host if request.client else "unknown"
        now = time.monotonic()
        window = _calls[bucket][ip]
        while window and now - window[0] >= per_seconds:
            window.popleft()
        if len(window) >= max_calls:
            retry_after = int(per_seconds - (now - window[0])) + 1
            raise LocalizedHTTPException(
                status_code=429,
                error_code="RATE_LIMITED",
                headers={"Retry-After": str(retry_after)},
            )
        window.append(now)

    return dependency


def reset() -> None:
    """Forget every counter (tests)."""
    _calls.clear()
