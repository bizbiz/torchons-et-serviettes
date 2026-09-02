"""Tests for the app-version gate (app/utils/version_gate.py).

Invariant under test: **a client below the threshold cannot reach a data route.**

Paths by which that invariant can be violated -- enumerated from the middleware's
own branches (`dispatch`, `_parse_version_code`, `EXEMPT_PREFIXES`) plus its
mounting site in `app/main.py`, before any case was written:

  1. the middleware is never mounted on the real app          -> covered
  2. the threshold is 0, so `dispatch` returns early          -> covered (intended no-op)
  3. the header is absent                                     -> covered; DELIBERATELY let
     through (see 3b below)
  4. the header is present but unparseable                    -> covered; deliberately let
     through, same reason
  5. the request path matches an exempt prefix                -> covered
  6. a data route whose path merely STARTS WITH an exempt one -> covered; `startswith`
     makes "/api/healthcheck-data" exempt without anyone intending it
  7. off-by-one at the boundary (>= vs >)                      -> covered
  8. a client that lies about its version code                -> NOT covered, not coverable:
     the client declares its own version. Stated in the module docstring; this gate
     stops a buggy build among trusted users, not an attacker holding the APK.
  9. middleware ordering lets a route answer before the gate  -> NOT covered by a case:
     `add_middleware` wraps outward, so the gate added after CORS sits outside it and
     no router can answer first. Structural, and a test would only restate Starlette.
 10. non-HTTP scopes (websocket) bypassing BaseHTTPMiddleware -> NOT covered: this API
     exposes no websocket route. Revisit if one is added.

Cases run on a throwaway FastAPI app, not the shared one: the threshold is read
once at startup, so several thresholds cannot coexist on a single app -- and the
gate needs neither database nor auth to be judged. Path 1 is the exception, and
it is the one that matters most in practice: a flawless middleware nobody wired
in is exactly what a unit test of the class cannot see.
"""

from __future__ import annotations

import httpx
import pytest
from fastapi import FastAPI

from app.utils.version_gate import VERSION_HEADER, VersionGateMiddleware


def _make_app(min_version_code: int, download_url: str = "https://example.test/apk") -> FastAPI:
    gated = FastAPI()
    gated.add_middleware(
        VersionGateMiddleware,
        min_version_code=min_version_code,
        download_url=download_url,
    )

    @gated.get("/api/tasks")
    async def tasks():
        return {"ok": True}

    @gated.get("/api/health")
    async def health():
        return {"status": "ok"}

    # Path 6: a data route sharing the opening characters of an exempt prefix.
    @gated.get("/api/healthcheck-data")
    async def healthcheck_data():
        return {"secret": True}

    return gated


async def _get(app: FastAPI, path: str, headers: dict | None = None) -> httpx.Response:
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as c:
        return await c.get(path, headers=headers or {})


@pytest.mark.asyncio
async def test_gate_disabled_lets_everything_through():
    """Path 2. 0 is the default and must be a true no-op: deploying changes nothing."""
    app = _make_app(min_version_code=0)
    resp = await _get(app, "/api/tasks", {VERSION_HEADER: "1"})
    assert resp.status_code == 200


@pytest.mark.asyncio
async def test_missing_header_is_let_through():
    """Path 3. THE rule that keeps this feature from bricking the fleet on day one.

    Every APK built before the gate existed sends no header. Refusing those would
    lock out every phone in the field the moment the gate is switched on, before a
    fixed APK exists -- and these APKs are sideloaded, so there is no store to push
    the fix through. The hole is deliberate and closes on its own once every build
    in circulation sends the header.
    """
    app = _make_app(min_version_code=999)
    resp = await _get(app, "/api/tasks")
    assert resp.status_code == 200


@pytest.mark.asyncio
async def test_malformed_header_is_let_through():
    """Path 4. Refusing on a value we failed to parse would fence out clients for
    a reason unrelated to their version."""
    app = _make_app(min_version_code=999)
    for raw in ("", "abc", "1.2.3", "  ", "95.0"):
        resp = await _get(app, "/api/tasks", {VERSION_HEADER: raw})
        assert resp.status_code == 200, f"header {raw!r} should be treated as absent"


@pytest.mark.asyncio
async def test_old_client_is_refused_with_426():
    """The invariant itself."""
    app = _make_app(min_version_code=96)
    resp = await _get(app, "/api/tasks", {VERSION_HEADER: "95"})
    assert resp.status_code == 426
    body = resp.json()
    assert body["code"] == "app_update_required"
    assert body["detail"] == "app_update_required"  # shape every other error has
    assert body["min_version_code"] == 96
    assert body["your_version_code"] == 95
    assert body["download_url"] == "https://example.test/apk"


@pytest.mark.asyncio
async def test_client_at_threshold_passes():
    """Path 7. The threshold is the OLDEST ACCEPTED build, not the first refused
    one. Off by one here silently fences out the build just shipped."""
    app = _make_app(min_version_code=95)
    resp = await _get(app, "/api/tasks", {VERSION_HEADER: "95"})
    assert resp.status_code == 200


@pytest.mark.asyncio
async def test_newer_client_passes():
    app = _make_app(min_version_code=95)
    resp = await _get(app, "/api/tasks", {VERSION_HEADER: "200"})
    assert resp.status_code == 200


@pytest.mark.asyncio
async def test_health_stays_reachable_for_a_refused_client():
    """Path 5. A refused client must be able to learn WHAT is asked of it.

    With every route closed, a badly chosen threshold becomes a silent outage
    that can no longer be diagnosed from the outside.
    """
    app = _make_app(min_version_code=999)
    resp = await _get(app, "/api/health", {VERSION_HEADER: "1"})
    assert resp.status_code == 200


@pytest.mark.asyncio
async def test_route_merely_prefixed_by_an_exempt_path_is_still_gated():
    """Path 6. `startswith` exempts by string, not by route.

    Nothing today is named "/api/healthcheck-data", which is the point: the day
    someone adds a route whose path opens with the characters of an exempt one,
    it would leave the gate silently -- no error, no warning, just an open door.
    """
    app = _make_app(min_version_code=999)
    resp = await _get(app, "/api/healthcheck-data", {VERSION_HEADER: "1"})
    assert resp.status_code == 426


@pytest.mark.asyncio
async def test_middleware_is_actually_mounted_on_the_real_app():
    """Path 1. The gate can be flawless and still do nothing if nobody wired it in."""
    from app.main import app as real_app

    mounted = [m.cls for m in real_app.user_middleware]
    assert VersionGateMiddleware in mounted


@pytest.mark.asyncio
async def test_health_exposes_the_threshold():
    """The client reads this to know the gate exists before it is fenced out."""
    from app.main import app as real_app

    transport = httpx.ASGITransport(app=real_app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as c:
        resp = await c.get("/api/health")
    assert resp.status_code == 200
    assert "min_app_version_code" in resp.json()
