#!/usr/bin/env python3
"""Credential-safe OAuth/MCP smoke for the ZenTao read-only client.

Only status and capability assertions are printed. Credentials, OAuth values,
identity fields and business payloads are never printed or persisted.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: ANN001
        return None


def load_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key] = value
    return values


def request(opener: urllib.request.OpenerDirector, url: str, method: str = "GET", *,
            headers: dict[str, str] | None = None, data: bytes | None = None) -> tuple[int, Any, bytes]:
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    try:
        with opener.open(req, timeout=45) as response:
            return response.status, response.headers, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.headers, error.read()


def json_request(opener: urllib.request.OpenerDirector, url: str, method: str = "GET", *,
                 headers: dict[str, str] | None = None, payload: object | None = None) -> tuple[int, Any, Any]:
    merged = {"Accept": "application/json", **(headers or {})}
    data = None
    if payload is not None:
        merged["Content-Type"] = "application/json"
        data = json.dumps(payload, ensure_ascii=False).encode()
    status, response_headers, body = request(opener, url, method, headers=merged, data=data)
    return status, response_headers, json.loads(body) if body else None


def cookies(headers: Any, current: str = "") -> str:
    jar: dict[str, str] = {}
    for item in current.split(";"):
        if "=" in item:
            key, value = item.strip().split("=", 1)
            jar[key] = value
    for raw in headers.get_all("Set-Cookie") or []:
        pair = raw.split(";", 1)[0]
        if "=" in pair:
            key, value = pair.split("=", 1)
            jar[key] = value
    return "; ".join(f"{key}={value}" for key, value in jar.items())


def rpc(body: bytes) -> dict[str, Any]:
    text = body.decode("utf-8")
    for line in text.splitlines():
        if line.startswith("data:"):
            return json.loads(line[5:].strip())
    return json.loads(text)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env", required=True, type=Path)
    parser.add_argument("--base", default="http://127.0.0.1:8080")
    args = parser.parse_args()
    values = load_env(args.env)
    username = values["BOOTSTRAP_ADMIN_USERNAME"]
    password = values["BOOTSTRAP_ADMIN_PASSWORD"]
    resource = values["MCP_RESOURCE_URI"]
    issuer = values["MCP_ISSUER"]
    origin = values["MCP_ALLOWED_ORIGINS"].split(",", 1)[0]
    public_host = urllib.parse.urlparse(resource).netloc
    opener = urllib.request.build_opener(NoRedirect())

    status, headers, csrf = json_request(opener, args.base + "/api/v1/auth/csrf")
    assert status == 200
    browser_cookie = cookies(headers)
    status, headers, _ = json_request(opener, args.base + "/api/v1/auth/login", "POST", headers={
        "Cookie": browser_cookie, "X-XSRF-TOKEN": csrf["token"]
    }, payload={"username": username, "password": password})
    assert status == 200
    browser_cookie = cookies(headers, browser_cookie)
    status, headers, csrf = json_request(opener, args.base + "/api/v1/auth/csrf", headers={"Cookie": browser_cookie})
    assert status == 200
    browser_cookie = cookies(headers, browser_cookie)

    callback = "http://127.0.0.1:37642/index.php?m=yinggecloud&f=oauthCallback"
    client_name = "英格科技项目平台（禅道只读）-UAT"
    status, _, registered = json_request(opener, args.base + "/oauth2/register", "POST", payload={
        "client_name": client_name,
        "redirect_uris": [callback],
        "grant_types": ["authorization_code", "refresh_token"],
        "response_types": ["code"],
        "token_endpoint_auth_method": "none",
    })
    assert status == 201 and registered["token_endpoint_auth_method"] == "none"
    client_id = registered["client_id"]
    verifier = "zentao-readonly-uat-verifier-abcdefghijklmnopqrstuvwxyz-0123456789"
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    state = "zentao-readonly-state"
    query = urllib.parse.urlencode({
        "response_type": "code", "client_id": client_id, "redirect_uri": callback,
        "scope": "yingge.read yingge.admin.read", "state": state,
        "code_challenge": challenge, "code_challenge_method": "S256", "resource": resource,
    })
    status, headers, _ = request(opener, args.base + "/oauth2/authorize?" + query)
    assert status == 302
    request_id = urllib.parse.parse_qs(urllib.parse.urlparse(headers["Location"]).query)["request"][0]
    status, _, consent = json_request(opener, args.base + f"/api/v1/oauth/requests/{request_id}",
                                      headers={"Cookie": browser_cookie})
    assert status == 200 and consent["clientName"] == client_name
    assert set(consent["grantableScopes"]) == {"yingge.read", "yingge.admin.read"}
    status, _, decision = json_request(opener, args.base + f"/api/v1/oauth/requests/{request_id}/approve", "POST",
                                       headers={"Cookie": browser_cookie, "X-XSRF-TOKEN": csrf["token"]})
    assert status == 200
    callback_query = urllib.parse.parse_qs(urllib.parse.urlparse(decision["redirectUrl"]).query)
    assert callback_query["state"][0] == state
    code = callback_query["code"][0]

    token_form = urllib.parse.urlencode({
        "grant_type": "authorization_code", "client_id": client_id, "code": code,
        "redirect_uri": callback, "code_verifier": verifier,
    }).encode()
    status, _, body = request(opener, args.base + "/oauth2/token", "POST", headers={
        "Accept": "application/json", "Content-Type": "application/x-www-form-urlencoded"
    }, data=token_form)
    assert status == 200
    token = json.loads(body)
    assert set(token["scope"].split()) == {"yingge.read", "yingge.admin.read"}

    def mcp(payload: object, session: str | None = None, access: str | None = None,
            method: str = "POST") -> tuple[int, Any, bytes]:
        headers = {
            "Authorization": "Bearer " + (access or token["access_token"]),
            "Accept": "application/json, text/event-stream", "Content-Type": "application/json",
            "Origin": origin, "Host": public_host,
        }
        if session:
            headers["mcp-session-id"] = session
        return request(opener, args.base + "/mcp", method, headers=headers,
                       data=json.dumps(payload).encode() if method == "POST" else None)

    initialize = {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
        "protocolVersion": "2025-11-25", "capabilities": {},
        "clientInfo": {"name": "yingge-zentao-readonly-uat", "version": "1"},
    }}
    status, headers, body = mcp(initialize)
    assert status == 200 and rpc(body)["result"]["protocolVersion"] == "2025-11-25"
    session = headers["mcp-session-id"]

    def call(request_id_value: int, method: str, params: object) -> dict[str, Any]:
        call_status, _, call_body = mcp({
            "jsonrpc": "2.0", "id": request_id_value, "method": method, "params": params
        }, session)
        assert call_status == 200
        return rpc(call_body)

    listed = call(2, "tools/list", {})
    tools = listed["result"]["tools"]
    names = {item["name"] for item in tools}
    assert "yingge_identity_me" in names and "yingge_leadership_dashboard_get" in names
    assert "yingge_admin_members" in names
    assert all(item.get("annotations", {}).get("readOnlyHint") is True for item in tools)
    assert not any(item.get("annotations", {}).get("destructiveHint") for item in tools)
    assert not any(name.startswith("yingge_ai_") for name in names)
    for index, (name, arguments) in enumerate((
        ("yingge_identity_me", {}),
        ("yingge_dashboard_get", {}),
        ("yingge_leadership_dashboard_get", {"days": 30}),
        ("yingge_admin_members", {}),
    ), start=10):
        result = call(index, "tools/call", {"name": name, "arguments": arguments})
        assert result["result"]["structuredContent"]["ok"] is True

    refresh_form = urllib.parse.urlencode({
        "grant_type": "refresh_token", "client_id": client_id, "refresh_token": token["refresh_token"]
    }).encode()
    status, _, rotated_body = request(opener, args.base + "/oauth2/token", "POST", headers={
        "Accept": "application/json", "Content-Type": "application/x-www-form-urlencoded"
    }, data=refresh_form)
    assert status == 200
    rotated = json.loads(rotated_body)
    status, _, _ = request(opener, args.base + "/oauth2/token", "POST", headers={
        "Accept": "application/json", "Content-Type": "application/x-www-form-urlencoded"
    }, data=refresh_form)
    assert status == 400
    revoke_form = urllib.parse.urlencode({"client_id": client_id, "token": rotated["access_token"]}).encode()
    status, _, _ = request(opener, args.base + "/oauth2/revoke", "POST", headers={
        "Accept": "application/json", "Content-Type": "application/x-www-form-urlencoded"
    }, data=revoke_form)
    assert status == 200
    status, _, _ = mcp(initialize, access=rotated["access_token"])
    assert status == 401

    # Remove the smoke grant through the normal owner API. No business record is created.
    status, _, grants = json_request(opener, args.base + "/api/v1/agent/authorizations",
                                     headers={"Cookie": browser_cookie})
    assert status == 200
    cleaned = 0
    for grant in grants:
        if grant.get("clientName") == client_name:
            if grant.get("status") == "ACTIVE":
                status, _, _ = request(opener, args.base + f"/api/v1/agent/authorizations/{grant['id']}", "DELETE",
                                       headers={"Cookie": browser_cookie, "X-XSRF-TOKEN": csrf["token"]})
                assert status == 204
            cleaned += 1
    assert cleaned >= 1
    print(json.dumps({
        "status": "PASS", "issuer": issuer, "protocol": "2025-11-25",
        "scopeIsolation": True, "writeToolsVisible": False, "aiToolsVisible": False,
        "identity": True, "leadership": True, "adminRead": True,
        "refreshRotation": True, "revocation": True, "grantCleanup": cleaned,
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
