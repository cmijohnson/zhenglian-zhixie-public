#!/usr/bin/env python3
"""Credential-safe OAuth/MCP smoke for the isolated UAT stack.

The script prints only status/count summaries. Access tokens, refresh tokens,
credentials, customer content and AI output are never printed or written.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from typing import Any


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: ANN001
        return None


def env(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            result[key] = value
    return result


def request(opener: urllib.request.OpenerDirector, url: str, method: str = "GET", *,
            headers: dict[str, str] | None = None, data: bytes | None = None) -> tuple[int, Any, bytes]:
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    try:
        with opener.open(req, timeout=60) as response:
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


def cookies_from(headers: Any, current: str | None = None) -> str:
    cookies: dict[str, str] = {}
    if current:
        for item in current.split(";"):
            if "=" in item:
                key, value = item.strip().split("=", 1)
                cookies[key] = value
    for raw in headers.get_all("Set-Cookie") or []:
        pair = raw.split(";", 1)[0]
        if "=" in pair:
            key, value = pair.split("=", 1)
            cookies[key] = value
    return "; ".join(f"{key}={value}" for key, value in cookies.items())


def rpc_json(body: bytes) -> dict[str, Any]:
    text = body.decode("utf-8")
    for line in text.splitlines():
        if line.startswith("data:"):
            return json.loads(line[5:].strip())
    return json.loads(text)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env", required=True, type=Path)
    parser.add_argument("--base", default="http://127.0.0.1:19090")
    parser.add_argument(
        "--skip-real-ai",
        action="store_true",
        help="Skip the real provider call during repeat post-deployment regression.",
    )
    parser.add_argument(
        "--read-only",
        action="store_true",
        help="Stop after OAuth, initialize, tool/resource discovery and read-only health checks.",
    )
    args = parser.parse_args()
    values = env(args.env)
    username = values["BOOTSTRAP_ADMIN_USERNAME"]
    password = values["BOOTSTRAP_ADMIN_PASSWORD"]
    resource = values.get("MCP_UAT_RESOURCE_URI") or values["MCP_RESOURCE_URI"]
    allowed_origins = values.get("MCP_UAT_ALLOWED_ORIGINS") or values["MCP_ALLOWED_ORIGINS"]
    origin = allowed_origins.split(",", 1)[0]
    public_host = urllib.parse.urlparse(resource).netloc
    opener = urllib.request.build_opener(NoRedirect())

    status, headers, csrf = json_request(opener, args.base + "/api/v1/auth/csrf")
    assert status == 200
    browser_cookie = cookies_from(headers)
    status, headers, _ = json_request(opener, args.base + "/api/v1/auth/login", "POST", headers={
        "Cookie": browser_cookie, "X-XSRF-TOKEN": csrf["token"]
    }, payload={"username": username, "password": password})
    assert status == 200
    browser_cookie = cookies_from(headers, browser_cookie)
    status, headers, csrf = json_request(opener, args.base + "/api/v1/auth/csrf", headers={
        "Cookie": browser_cookie
    })
    assert status == 200
    browser_cookie = cookies_from(headers, browser_cookie)

    callback = "http://127.0.0.1:43123/callback"
    status, _, registered = json_request(opener, args.base + "/oauth2/register", "POST", payload={
        "client_name": "Yingge MCP UAT",
        "redirect_uris": [callback],
        "grant_types": ["authorization_code", "refresh_token"],
        "response_types": ["code"],
        "token_endpoint_auth_method": "none",
    })
    assert status == 201
    client_id = registered["client_id"]
    verifier = "yingge-uat-verifier-abcdefghijklmnopqrstuvwxyz-0123456789"
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode("ascii")).digest()).rstrip(b"=").decode()
    query = urllib.parse.urlencode({
        "response_type": "code", "client_id": client_id, "redirect_uri": callback,
        "scope": "yingge.read yingge.write yingge.ai yingge.admin", "state": "uat-state",
        "code_challenge": challenge, "code_challenge_method": "S256", "resource": resource,
    })
    status, headers, _ = request(opener, args.base + "/oauth2/authorize?" + query)
    assert status == 302
    consent_location = headers["Location"]
    request_id = urllib.parse.parse_qs(urllib.parse.urlparse(consent_location).query)["request"][0]
    status, _, consent = json_request(opener, args.base + f"/api/v1/oauth/requests/{request_id}", headers={
        "Cookie": browser_cookie
    })
    assert status == 200 and consent["clientName"] == "Yingge MCP UAT"
    status, _, decision = json_request(opener, args.base + f"/api/v1/oauth/requests/{request_id}/approve", "POST",
                                       headers={"Cookie": browser_cookie, "X-XSRF-TOKEN": csrf["token"]})
    assert status == 200
    code = urllib.parse.parse_qs(urllib.parse.urlparse(decision["redirectUrl"]).query)["code"][0]

    token_form = urllib.parse.urlencode({
        "grant_type": "authorization_code", "client_id": client_id, "code": code,
        "redirect_uri": callback, "code_verifier": verifier,
    }).encode()
    status, _, token_body = request(opener, args.base + "/oauth2/token", "POST", headers={
        "Accept": "application/json", "Content-Type": "application/x-www-form-urlencoded"
    }, data=token_form)
    assert status == 200
    token = json.loads(token_body)

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

    initialize_payload = {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
        "protocolVersion": "2025-11-25", "capabilities": {},
        "clientInfo": {"name": "uat-smoke", "version": "1.0"},
    }}
    status, headers, body = mcp(initialize_payload)
    assert status == 200 and rpc_json(body)["result"]["protocolVersion"] == "2025-11-25"
    sessions = [headers["mcp-session-id"]]

    def call(session: str, request_id_value: int, method_name: str, params: object) -> tuple[int, dict[str, Any]]:
        call_status, _, call_body = mcp({
            "jsonrpc": "2.0", "id": request_id_value, "method": method_name, "params": params
        }, session)
        return call_status, rpc_json(call_body)

    status, listed = call(sessions[0], 2, "tools/list", {})
    assert status == 200
    tools = listed["result"]["tools"]
    names = {item["name"] for item in tools}
    assert len(names) >= 60 and "yingge_admin_members" in names and "yingge_customer_create" in names
    ai_schema = next(item["inputSchema"] for item in tools if item["name"] == "yingge_admin_ai_settings_update")
    assert "apiKey" not in json.dumps(ai_schema) and "clearApiKey" not in json.dumps(ai_schema)
    status, dashboard = call(sessions[0], 3, "tools/call", {
        "name": "yingge_dashboard_get", "arguments": {}
    })
    assert status == 200 and dashboard["result"]["structuredContent"]["ok"] is True
    status, resources = call(sessions[0], 4, "resources/read", {"uri": "yingge://health"})
    assert status == 200 and resources.get("result")
    status, availability = call(sessions[0], 5, "tools/call", {
        "name": "yingge_ai_availability", "arguments": {}
    })
    assert status == 200 and availability["result"]["structuredContent"]["ok"] is True

    if args.read_only:
        mcp({}, session=sessions[0], method="DELETE")
        revoke_form = urllib.parse.urlencode({
            "client_id": client_id,
            "token": token["access_token"],
        }).encode()
        status, _, _ = request(opener, args.base + "/oauth2/revoke", "POST", headers={
            "Accept": "application/json", "Content-Type": "application/x-www-form-urlencoded"
        }, data=revoke_form)
        assert status == 200
        status, _, _ = mcp(initialize_payload)
        assert status == 401
        status, _, grants = json_request(opener, args.base + "/api/v1/agent/authorizations", headers={
            "Cookie": browser_cookie,
        })
        assert status == 200
        revoked_grants = 0
        for grant in grants:
            if grant.get("clientName") != "Yingge MCP UAT" or grant.get("status") != "ACTIVE":
                continue
            status, _, _ = request(
                opener,
                args.base + f"/api/v1/agent/authorizations/{grant['id']}",
                "DELETE",
                headers={"Cookie": browser_cookie, "X-XSRF-TOKEN": csrf["token"]},
            )
            assert status == 204
            revoked_grants += 1
        assert revoked_grants >= 1
        print(json.dumps({
            "status": "PASS", "mode": "READ_ONLY", "protocol": "2025-11-25",
            "toolCount": len(names), "adminToolVisible": True, "aiKeyExcluded": True,
            "oauthTokenRevocation": True, "oauthGrantCleanup": revoked_grants,
        }, ensure_ascii=False))
        return

    # Exercise a real business write, idempotent replay and the complete human
    # approval flow. The fixture is archived immediately and no customer data is
    # printed by this script.
    fixture_suffix = uuid.uuid4().hex[:12]
    create_arguments = {
        "request": {
            "fullName": "MCP UAT 自动化客户 " + fixture_suffix,
            "qqId": "uat_" + fixture_suffix,
            "visibility": "PRIVATE",
            "sourceChannel": "MCP_UAT",
        },
        "idempotencyKey": "uat-create-" + fixture_suffix,
    }
    status, created = call(sessions[0], 6, "tools/call", {
        "name": "yingge_customer_create", "arguments": create_arguments
    })
    created_content = created["result"]["structuredContent"]
    assert status == 200 and created_content["ok"] is True, json.dumps(
        created_content.get("error", {"code": "UNKNOWN"}), ensure_ascii=False
    )
    customer_id = created_content["data"]["id"]
    status, replayed = call(sessions[0], 7, "tools/call", {
        "name": "yingge_customer_create", "arguments": create_arguments
    })
    assert status == 200 and replayed["result"]["structuredContent"]["data"]["id"] == customer_id

    # Call at least one real tool from every read-oriented business and
    # administration group against the isolated application services.  This
    # catches adapter/schema defects that tools/list alone cannot reveal.
    representative_calls = [
        ("yingge_customer_search", {}),
        ("yingge_customer_get", {"customerId": customer_id}),
        ("yingge_customer_collaboration_get", {"customerId": customer_id}),
        ("yingge_interaction_list", {"customerId": customer_id}),
        ("yingge_reminder_list", {}),
        ("yingge_task_list", {}),
        ("yingge_task_matrix", {}),
        ("yingge_content_overview", {}),
        ("yingge_content_circle_search", {}),
        ("yingge_content_senders", {}),
        ("yingge_daily_report_today", {}),
        ("yingge_daily_report_history", {}),
        ("yingge_announcement_list", {"includeArchived": True}),
        ("yingge_team_data_overview", {}),
        ("yingge_tag_list", {}),
        ("yingge_dictionary_list", {}),
        ("yingge_admin_members", {}),
        ("yingge_admin_teams", {}),
        ("yingge_admin_registration_codes", {}),
        ("yingge_admin_audit", {"page": 0, "size": 10}),
        ("yingge_admin_ai_settings_get", {}),
    ]
    for representative_id, (tool_name, arguments) in enumerate(representative_calls, start=100):
        status, representative = call(sessions[0], representative_id, "tools/call", {
            "name": tool_name, "arguments": arguments,
        })
        content = representative["result"]["structuredContent"]
        assert status == 200 and content["ok"] is True, json.dumps(
            {"tool": tool_name, "error": content.get("error", {"code": "UNKNOWN"})}, ensure_ascii=False
        )

    archive_arguments = {
        "customerId": customer_id,
        "idempotencyKey": "uat-archive-" + fixture_suffix,
    }
    status, requested = call(sessions[0], 8, "tools/call", {
        "name": "yingge_customer_archive", "arguments": archive_arguments
    })
    requested_content = requested["result"]["structuredContent"]
    assert status == 200 and requested_content["approvalRequired"] is True
    approval_id = requested_content["approval"]["approvalId"]
    status, _, approved = json_request(
        opener, args.base + f"/api/v1/agent/approvals/{approval_id}/approve", "POST",
        headers={"Cookie": browser_cookie, "X-XSRF-TOKEN": csrf["token"]},
    )
    assert status == 200 and approved["status"] == "APPROVED"
    archive_arguments["approvalId"] = approval_id
    status, archived = call(sessions[0], 9, "tools/call", {
        "name": "yingge_customer_archive", "arguments": archive_arguments
    })
    assert status == 200 and archived["result"]["structuredContent"]["ok"] is True
    status, archive_replay = call(sessions[0], 10, "tools/call", {
        "name": "yingge_customer_archive", "arguments": archive_arguments
    })
    assert status == 200 and archive_replay["result"]["structuredContent"]["ok"] is True

    availability_data = availability["result"]["structuredContent"]["data"]
    real_ai_smoke = "SKIPPED_RECHECK" if args.skip_real_ai else "UNAVAILABLE"
    if availability_data.get("available") is True and not args.skip_real_ai:
        status, briefing = call(sessions[0], 11, "tools/call", {
            "name": "yingge_ai_daily_briefing", "arguments": {}
        })
        assert status == 200 and briefing["result"]["structuredContent"]["ok"] is True
        real_ai_smoke = "PASS"

    for index in range(4):
        status, headers, body = mcp(initialize_payload)
        assert status == 200
        session = headers["mcp-session-id"]
        sessions.append(session)
        status, result = call(session, 20 + index, "tools/call", {
            "name": "yingge_dashboard_get", "arguments": {}
        })
        assert status == 200 and result["result"]["structuredContent"]["ok"] is True
    status, headers, body = mcp(initialize_payload)
    assert status == 200
    sixth = headers["mcp-session-id"]
    status, limited = call(sixth, 30, "tools/call", {"name": "yingge_dashboard_get", "arguments": {}})
    assert status == 200 and "MCP_SESSION_LIMIT" in json.dumps(limited, ensure_ascii=False)

    for session in sessions + [sixth]:
        mcp({}, session=session, method="DELETE")

    status, headers, body = mcp(initialize_payload)
    assert status == 200 and rpc_json(body)["result"]["protocolVersion"] == "2025-11-25"
    replacement = headers["mcp-session-id"]
    status, replacement_result = call(replacement, 31, "tools/call", {
        "name": "yingge_dashboard_get", "arguments": {}
    })
    assert status == 200 and replacement_result["result"]["structuredContent"]["ok"] is True
    mcp({}, session=replacement, method="DELETE")

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
    status, _, _ = mcp(initialize_payload, access=rotated["access_token"])
    assert status == 401

    print(json.dumps({
        "status": "PASS", "protocol": "2025-11-25", "toolCount": len(names),
        "adminToolVisible": True, "aiKeyExcluded": True, "maxConcurrentSessions": 5,
        "refreshReplayRevokedFamily": True, "idempotentBusinessWrite": True,
        "humanApprovalArchive": True, "representativeGroupCalls": len(representative_calls),
        "realAiSmoke": real_ai_smoke,
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
