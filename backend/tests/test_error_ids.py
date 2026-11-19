"""Every error response carries a short id the owner can grep in the server logs.

Contract: header `X-Error-Id: E-XXXXX` on every status >= 400, the same value under
`error_id` in a JSON object body, one log line per error, and an unhandled exception
answers `internal_error` without leaking its message (app/utils/error_ids.py).

Paths by which an error response can leave without an id, and what covers each:
- HTTPException (handler of FastAPI)         : test_header_and_body... [/http], real app 404/401
- LocalizedHTTPException (our handler)       : test_header_and_body... [/localized]
- 422 request validation                     : test_header_and_body... [/validated]
- unhandled exception turned into 500        : test_header_and_body... [/boom], no-leak test
- JSONResponse returned directly (route)     : test_header_and_body... [/direct]
- JSONResponse returned by a middleware (426): test_version_gate_426_is_stamped_too
- non-object JSON body                       : test_non_object_body_gets_the_header_only
- non-error response must stay untouched     : test_success_has_no_error_id
- not covered: exception raised after the response started, the status line is already
  sent and cannot be replaced (the exception is re-raised untouched).
- not covered: non-JSON error bodies (plain text/HTML), they get the header only; the
  API answers JSON everywhere, and the branch is the same as the non-object one.
- not covered: websocket scopes, the API has none.
"""

import re

import httpx
import pytest
from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from structlog.testing import capture_logs

from app.auth import create_token
from app.utils.error_ids import ERROR_ID_ALPHABET, ErrorIdMiddleware, new_error_id
from app.utils.errors import LocalizedHTTPException, register_localized_error_handler
from app.utils.version_gate import VERSION_HEADER, VersionGateMiddleware
from tests.conftest import DEVICE_A, TEST_USER_ID

ID_RE = re.compile(r"^E-[" + ERROR_ID_ALPHABET + r"]{5}$")
SECRET_MESSAGE = "boom-secret-password-123"


class _Body(BaseModel):
    count: int


def _make_app() -> FastAPI:
    app = FastAPI()
    register_localized_error_handler(app)
    app.add_middleware(VersionGateMiddleware, min_version_code=5, download_url="https://x.test")
    app.add_middleware(ErrorIdMiddleware)

    @app.get("/http")
    async def http_error():
        raise HTTPException(status_code=404, detail="Introuvable")

    @app.get("/localized")
    async def localized():
        raise LocalizedHTTPException(status_code=409, error_code="DEVICE_REVOKED")

    @app.post("/validated")
    async def validated(body: _Body):
        return {"ok": True}

    @app.get("/boom")
    async def boom():
        raise RuntimeError(SECRET_MESSAGE)

    @app.get("/direct")
    async def direct():
        return JSONResponse(status_code=418, content={"detail": "teapot"})

    @app.get("/direct-list")
    async def direct_list():
        return JSONResponse(status_code=400, content=["not", "an", "object"])

    @app.get("/fine")
    async def fine():
        return {"ok": True}

    return app


async def _call(method: str, path: str, **kwargs) -> httpx.Response:
    transport = httpx.ASGITransport(app=_make_app())
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as c:
        return await c.request(method, path, headers={VERSION_HEADER: "9"}, **kwargs)


def _only_log(logs: list[dict]) -> dict:
    lines = [entry for entry in logs if entry.get("event") == "request_error"]
    assert len(lines) == 1, logs
    return lines[0]


def test_id_format():
    for _ in range(200):
        assert ID_RE.match(new_error_id())


@pytest.mark.parametrize(
    "method,path,kwargs,status",
    [
        ("GET", "/http", {}, 404),
        ("GET", "/localized", {}, 409),
        ("POST", "/validated", {"json": {"count": "abc"}}, 422),
        ("GET", "/boom", {}, 500),
        ("GET", "/direct", {}, 418),
    ],
)
async def test_header_and_body_carry_the_same_id_and_the_log_names_it(
    method, path, kwargs, status
):
    with capture_logs() as logs:
        resp = await _call(method, path, **kwargs)
    assert resp.status_code == status
    error_id = resp.headers["X-Error-Id"]
    assert ID_RE.match(error_id)
    assert resp.json()["error_id"] == error_id
    entry = _only_log(logs)
    assert entry["error_id"] == error_id
    assert entry["status"] == status
    assert entry["method"] == method
    assert entry["path"] == path
    assert entry["user_id"] == "-"
    assert entry["log_level"] == ("error" if status >= 500 else "warning")


async def test_existing_keys_are_kept():
    resp = await _call("GET", "/localized")
    body = resp.json()
    assert body["code"] == "DEVICE_REVOKED"
    assert "detail" in body
    assert (await _call("GET", "/http")).json()["detail"] == "Introuvable"


async def test_unhandled_exception_does_not_leak_and_logs_the_trace():
    with capture_logs() as logs:
        resp = await _call("GET", "/boom")
    assert resp.status_code == 500
    assert resp.json() == {"detail": "internal_error", "error_id": resp.headers["X-Error-Id"]}
    assert SECRET_MESSAGE not in resp.text
    entry = _only_log(logs)
    assert isinstance(entry["exc_info"], RuntimeError)
    assert entry["detail"] == "internal_error"


async def test_validation_log_does_not_echo_the_input():
    with capture_logs() as logs:
        resp = await _call("POST", "/validated", json={"count": SECRET_MESSAGE})
    assert resp.status_code == 422
    assert SECRET_MESSAGE not in str(_only_log(logs))


async def test_version_gate_426_is_stamped_too():
    transport = httpx.ASGITransport(app=_make_app())
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as c:
        resp = await c.get("/fine", headers={VERSION_HEADER: "1"})
    assert resp.status_code == 426
    assert ID_RE.match(resp.headers["X-Error-Id"])
    assert resp.json()["error_id"] == resp.headers["X-Error-Id"]
    assert resp.json()["code"] == "app_update_required"


async def test_non_object_body_gets_the_header_only():
    resp = await _call("GET", "/direct-list")
    assert resp.status_code == 400
    assert ID_RE.match(resp.headers["X-Error-Id"])
    assert resp.json() == ["not", "an", "object"]


async def test_success_has_no_error_id():
    resp = await _call("GET", "/fine")
    assert resp.status_code == 200
    assert "X-Error-Id" not in resp.headers
    assert resp.json() == {"ok": True}


async def test_user_id_is_logged_from_a_valid_token_and_the_token_is_not():
    token = create_token(TEST_USER_ID, DEVICE_A)
    transport = httpx.ASGITransport(app=_make_app())
    with capture_logs() as logs:
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as c:
            await c.get(
                "/http", headers={VERSION_HEADER: "9", "Authorization": f"Bearer {token}"}
            )
    entry = _only_log(logs)
    assert entry["user_id"] == TEST_USER_ID
    assert token not in str(entry)


async def test_real_app_errors_carry_the_id(client):
    with capture_logs() as logs:
        missing = await client.get("/api/this-route-does-not-exist")
        unauthenticated = await client.get("/api/tasks")
    for resp in (missing, unauthenticated):
        assert resp.status_code >= 400
        assert ID_RE.match(resp.headers["X-Error-Id"])
        assert resp.json()["error_id"] == resp.headers["X-Error-Id"]
    ids = {e["error_id"] for e in logs if e.get("event") == "request_error"}
    assert {missing.headers["X-Error-Id"], unauthenticated.headers["X-Error-Id"]} <= ids
