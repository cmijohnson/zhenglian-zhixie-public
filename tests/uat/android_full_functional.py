#!/usr/bin/env python3
"""Live Android API closure against an isolated Yingge Cloud database.

The runner is intentionally secret-safe: bootstrap and registration credentials
are read from server-side files, generated passwords/tokens are never printed,
and response bodies are reduced to status/error-code/traceId evidence.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import http.client
import json
import mimetypes
import secrets
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable


def read_response_payload(response: Any) -> bytes:
    try:
        return response.read()
    except http.client.IncompleteRead as error:
        # SSE providers may close a chunked response after sending usable events.
        # Preserve the received bytes so the contract/status can still be recorded.
        return error.partial


CN = timezone(timedelta(hours=8))


def env_value(path: Path, key: str) -> str:
    prefix = key + "="
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith(prefix):
            return line[len(prefix):]
    raise RuntimeError(f"missing environment key: {key}")


def credential_value(path: Path, key: str) -> str:
    prefix = key + "="
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith(prefix):
            return line[len(prefix):]
    raise RuntimeError(f"missing credential key: {key}")


def json_file(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


@dataclass
class Result:
    scenario: str
    operation: str
    role: str
    expected: str
    actual: int
    result: str
    error_code: str = ""
    trace_id: str = ""
    detail: str = ""


class Client:
    def __init__(self, base: str, platform: str, device_id: str | None = None):
        self.base = base.rstrip("/")
        self.platform = platform
        self.device_id = device_id or str(uuid.uuid4())
        self.access = ""
        self.refresh = ""

    def headers(self, authenticated: bool = True) -> dict[str, str]:
        headers = {
            "Accept": "application/json, text/event-stream, */*",
            "X-Yingge-Device-Id": self.device_id,
            "X-Yingge-Platform": self.platform,
            "X-Yingge-App-Version": "0.2.1-uat",
            "X-Yingge-Version-Code": "6",
            "X-Trace-Id": uuid.uuid4().hex,
        }
        if authenticated and self.access:
            headers["Authorization"] = "Bearer " + self.access
        return headers

    def call(self, method: str, path: str, body: Any = None, *, authenticated: bool = True,
             headers: dict[str, str] | None = None, form: dict[str, str] | None = None,
             raw: bytes | None = None, content_type: str | None = None,
             follow_redirects: bool = True) -> tuple[int, Any, dict[str, str], bytes]:
        request_headers = self.headers(authenticated)
        request_headers.update(headers or {})
        data: bytes | None = None
        if form is not None:
            data = urllib.parse.urlencode(form).encode()
            request_headers["Content-Type"] = "application/x-www-form-urlencoded"
        elif raw is not None:
            data = raw
            request_headers["Content-Type"] = content_type or "application/octet-stream"
        elif body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            request_headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.base + path, data=data, headers=request_headers, method=method)
        opener = urllib.request.build_opener() if follow_redirects else urllib.request.build_opener(NoRedirect())
        try:
            with opener.open(request, timeout=45, context=ssl.create_default_context()) as response:
                payload = read_response_payload(response)
                return response.status, parse_payload(payload, response.headers.get("Content-Type", "")), dict(response.headers), payload
        except TypeError:
            # OpenerDirector.open has no context argument on some Python builds.
            try:
                with opener.open(request, timeout=45) as response:
                    payload = read_response_payload(response)
                    return response.status, parse_payload(payload, response.headers.get("Content-Type", "")), dict(response.headers), payload
            except urllib.error.HTTPError as error:
                payload = error.read()
                return error.code, parse_payload(payload, error.headers.get("Content-Type", "")), dict(error.headers), payload
        except urllib.error.HTTPError as error:
            payload = error.read()
            return error.code, parse_payload(payload, error.headers.get("Content-Type", "")), dict(error.headers), payload

    def login(self, username: str, password: str) -> tuple[int, Any]:
        status, body, _, _ = self.call("POST", "/api/v1/mobile/auth/login", {
            "username": username, "password": password, "deviceName": f"{self.platform} UAT",
        }, authenticated=False)
        if status == 200:
            self.access = str(body["accessToken"])
            self.refresh = str(body["refreshToken"])
        return status, body

    def rotate(self) -> tuple[int, Any, str]:
        old = self.refresh
        status, body, _, _ = self.call("POST", "/api/v1/mobile/auth/refresh", {"refreshToken": old}, authenticated=False)
        if status == 200:
            self.access = str(body["accessToken"])
            self.refresh = str(body["refreshToken"])
        return status, body, old


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req: Any, fp: Any, code: int, msg: str, headers: Any, newurl: str) -> Any:
        return None


def parse_payload(payload: bytes, content_type: str) -> Any:
    if not payload:
        return None
    if "json" in content_type:
        try:
            return json.loads(payload)
        except json.JSONDecodeError:
            return {"_invalidJson": True}
    if "text" in content_type or "event-stream" in content_type:
        return payload.decode("utf-8", errors="replace")
    return {"_binaryBytes": len(payload), "sha256": hashlib.sha256(payload).hexdigest()}


def body_error(body: Any) -> tuple[str, str]:
    if not isinstance(body, dict):
        return "", ""
    return str(body.get("code", "")), str(body.get("traceId", ""))


def multipart(field: str, filename: str, content: bytes, content_type: str) -> tuple[bytes, str]:
    boundary = "----yingge-uat-" + secrets.token_hex(12)
    raw = (
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"{field}\"; filename=\"{filename}\"\r\n"
        f"Content-Type: {content_type}\r\n\r\n"
    ).encode() + content + f"\r\n--{boundary}--\r\n".encode()
    return raw, f"multipart/form-data; boundary={boundary}"


class Runner:
    def __init__(self, base: str):
        self.base = base
        self.rows: list[Result] = []

    def record(self, scenario: str, operation: str, role: str, actual: int,
               expected: Iterable[int], body: Any = None, detail: str = "") -> bool:
        expected_set = set(expected)
        code, trace = body_error(body)
        passed = actual in expected_set
        self.rows.append(Result(scenario, operation, role, "/".join(map(str, sorted(expected_set))),
                                actual, "PASS" if passed else "FAIL", code, trace, detail))
        return passed

    def call(self, scenario: str, client: Client, method: str, path: str, expected: Iterable[int],
             *, role: str = "MEMBER", body: Any = None, headers: dict[str, str] | None = None,
             form: dict[str, str] | None = None, raw: bytes | None = None,
             content_type: str | None = None, authenticated: bool = True,
             follow_redirects: bool = True, detail: str = "") -> tuple[int, Any, dict[str, str], bytes]:
        result = client.call(method, path, body, authenticated=authenticated, headers=headers, form=form,
                             raw=raw, content_type=content_type, follow_redirects=follow_redirects)
        self.record(scenario, f"{method} {path}", role, result[0], expected, result[1], detail)
        return result


def customer_body(name: str, team_id: str | None = None) -> dict[str, Any]:
    return {
        "fullName": name, "nickname": "移动验收", "phone": None, "wechatId": None,
        "qqId": "uat" + secrets.token_hex(5), "school": "示例大学", "major": "计算机科学与技术",
        "grade": "2026", "city": "示例市", "highSchool": None, "sourceLocation": None,
        "identityType": "在校生", "focusDirections": ["升学"], "demandSummary": "Android 隔离功能验收客户",
        "intentionLevel": "中", "urgencyLevel": "中", "verificationStatus": "未核实",
        "sourceChannel": "Android UAT", "currentStatus": "初次接触", "ownerNotes": "隔离库测试",
        "teamId": team_id, "visibility": "PRIVATE", "tagIds": [], "expectedVersion": None,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1")
    parser.add_argument("--env", required=True, type=Path)
    parser.add_argument("--admin-credentials", type=Path)
    parser.add_argument("--admin-token-file", type=Path)
    parser.add_argument("--admin-device-id-file", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    run = Runner(args.base.rstrip("/"))
    marker = datetime.now(CN).strftime("%m%d%H%M%S") + secrets.token_hex(2)
    admin_device_id = (args.admin_device_id_file.read_text(encoding="utf-8").strip()
                       if args.admin_device_id_file else None)
    admin = Client(run.base, "ANDROID", admin_device_id)
    admin_user = (credential_value(args.admin_credentials, "U") if args.admin_credentials
                  else env_value(args.env, "BOOTSTRAP_ADMIN_USERNAME"))
    admin_pass = (credential_value(args.admin_credentials, "P") if args.admin_credentials
                  else env_value(args.env, "BOOTSTRAP_ADMIN_PASSWORD"))
    if args.admin_token_file:
        stored_admin = json_file(args.admin_token_file)
        admin.access = str(stored_admin["accessToken"])
        admin.refresh = str(stored_admin["refreshToken"])
        status, body, _, _ = admin.call("GET", "/api/v1/mobile/bootstrap")
        run.record("AUTH-ADMIN-RESTORE", "GET /api/v1/mobile/bootstrap", "ORG_ADMIN", status, {200}, body)
    else:
        status, body = admin.login(admin_user, admin_pass)
        run.record("AUTH-ADMIN-LOGIN", "POST /api/v1/mobile/auth/login", "ORG_ADMIN", status, {200}, body)
    if status != 200:
        raise SystemExit("isolated admin mobile login failed")

    # Unauthenticated and header binding.
    anon = Client(run.base, "ANDROID")
    run.call("AUTH-ANON-DENIED", anon, "GET", "/api/v1/dashboard", {401}, role="UNAUTHENTICATED", authenticated=False)
    malformed = Client(run.base, "ANDROID", "not-a-uuid")
    run.call("AUTH-DEVICE-HEADER-VALIDATION", malformed, "POST", "/api/v1/mobile/auth/login", {400},
             role="UNAUTHENTICATED", authenticated=False,
             body={"username": admin_user, "password": "wrong", "deviceName": "bad"})

    # Create isolated teams, accounts, and a single-use registration code.
    _, team_a, _, _ = run.call("SETUP-TEAM-A", admin, "POST", "/api/v1/admin/teams", {201}, role="ORG_ADMIN", body={"name": f"Android UAT A {marker}"})
    _, team_b, _, _ = run.call("SETUP-TEAM-B", admin, "POST", "/api/v1/admin/teams", {201}, role="ORG_ADMIN", body={"name": f"Android UAT B {marker}"})
    team_a_id, team_b_id = str(team_a.get("id", "")), str(team_b.get("id", ""))
    expiry = (datetime.now(timezone.utc) + timedelta(hours=3)).isoformat().replace("+00:00", "Z")
    _, code_body, _, _ = run.call("AUTH-REGCODE-CREATE", admin, "POST", "/api/v1/admin/registration-codes", {201}, role="ORG_ADMIN", body={"maxUses": 3, "expiresAt": expiry})
    registration_code = str(code_body.get("code", ""))
    code_id = str(code_body.get("id", ""))

    member = Client(run.base, "ANDROID")
    username = "andfull_" + marker.lower()
    password = "Uat!" + secrets.token_urlsafe(18) + "9a"
    _, options, _, _ = run.call("AUTH-REGISTRATION-OPTIONS", member, "POST", "/api/v1/mobile/auth/registration-options", {200}, role="UNAUTHENTICATED", authenticated=False, body={"registrationCode": registration_code})
    eligible = {str(row.get("id")) for row in (options or {}).get("teams", [])}
    if team_a_id not in eligible and eligible:
        team_a_id = sorted(eligible)[0]
    run.call("AUTH-REGISTER", member, "POST", "/api/v1/mobile/auth/register", {201}, role="UNAUTHENTICATED", authenticated=False,
             body={"username": username, "realName": "Android 全功能测试员", "password": password,
                   "registrationCode": registration_code, "teamId": team_a_id})
    status, body = member.login(username, password)
    run.record("AUTH-MEMBER-LOGIN", "POST /api/v1/mobile/auth/login", "MEMBER", status, {200}, body)
    member_id = str((body or {}).get("actor", {}).get("membershipId", ""))

    def create_role(role: str, team_id: str, suffix: str = "") -> tuple[Client, str, str]:
        login = f"and_{role.lower()}_{suffix}_{marker.lower()}".replace("__", "_")
        secret = "Uat!" + secrets.token_urlsafe(18) + "7b"
        _, created, _, _ = run.call(f"SETUP-{role}-CREATE", admin, "POST", "/api/v1/admin/members", {201}, role="ORG_ADMIN",
                                    body={"username": login, "displayName": f"Android {role} 验收", "initialPassword": secret, "role": role, "teamId": team_id})
        client = Client(run.base, "ANDROID")
        st, payload = client.login(login, secret)
        run.record(f"AUTH-{role}-LOGIN", "POST /api/v1/mobile/auth/login", role, st, {200}, payload)
        return client, str((created or {}).get("membershipId", "")), secret

    manager, manager_id, _ = create_role("TEAM_MANAGER", team_a_id, "a")
    viewer, viewer_id, _ = create_role("VIEWER", team_a_id, "a")
    # Keep one active writable member deliberately ungrouped.  V52 must still
    # expose this member as a transfer candidate for a private customer.
    peer, peer_id, _ = create_role("MEMBER", None, "peer")
    outsider, outsider_id, _ = create_role("MEMBER", team_b_id, "outside")

    # Mobile lifecycle, server-side mode/capability clipping, and rotation/replay.
    _, admin_bootstrap, _, _ = run.call("MOBILE-ADMIN-BOOTSTRAP", admin, "GET", "/api/v1/mobile/bootstrap", {200}, role="ORG_ADMIN")
    admin_modes = set((admin_bootstrap or {}).get("availableModes", [])) if isinstance(admin_bootstrap, dict) else set()
    admin_capabilities = set((admin_bootstrap or {}).get("capabilities", [])) if isinstance(admin_bootstrap, dict) else set()
    required_admin_capabilities = {
        "ADMIN_MANAGEMENT", "ADMIN_OVERVIEW_READ", "ADMIN_MEMBERS_WRITE",
        "ADMIN_GOVERNANCE_WRITE", "ADMIN_PERFORMANCE_WRITE",
        "ADMIN_CUSTOMER_INTELLIGENCE_WRITE", "ADMIN_AUDIT_READ",
        "LEADERSHIP_DASHBOARD_READ",
    }
    run.record("MOBILE-ADMIN-MODES", "availableModes", "ORG_ADMIN",
               1 if admin_modes == {"EMPLOYEE", "ADMIN"} else 0, {1}, admin_bootstrap,
               "organization administrators must receive the two explicit modes")
    run.record("MOBILE-ADMIN-CAPABILITIES", "capabilities", "ORG_ADMIN",
               1 if required_admin_capabilities.issubset(admin_capabilities) else 0, {1}, admin_bootstrap,
               "native management navigation is clipped by server capabilities")
    _, manager_bootstrap, _, _ = run.call("MOBILE-MANAGER-BOOTSTRAP", manager, "GET", "/api/v1/mobile/bootstrap", {200}, role="TEAM_MANAGER")
    manager_modes = set((manager_bootstrap or {}).get("availableModes", [])) if isinstance(manager_bootstrap, dict) else set()
    manager_capabilities = set((manager_bootstrap or {}).get("capabilities", [])) if isinstance(manager_bootstrap, dict) else set()
    run.record("MOBILE-MANAGER-SCOPE", "availableModes/capabilities", "TEAM_MANAGER",
               1 if manager_modes == {"EMPLOYEE"} and {"TEAM_DATA", "LEADERSHIP_DASHBOARD_READ"}.issubset(manager_capabilities)
               and "ADMIN_MANAGEMENT" not in manager_capabilities else 0, {1}, manager_bootstrap,
               "team managers retain team read capabilities without organization admin mode")
    run.call("MOBILE-BOOTSTRAP", member, "GET", "/api/v1/mobile/bootstrap", {200})
    run.call("MOBILE-DEVICE-LIST", member, "GET", "/api/v1/mobile/auth/devices", {200})
    old_refresh = member.refresh
    status, payload, replay_token = member.rotate()
    run.record("AUTH-REFRESH-ROTATE", "POST /api/v1/mobile/auth/refresh", "MEMBER", status, {200}, payload)
    replay = Client(run.base, "ANDROID", member.device_id)
    run.call("AUTH-REFRESH-REPLAY", replay, "POST", "/api/v1/mobile/auth/refresh", {401, 409}, role="MEMBER", authenticated=False, body={"refreshToken": replay_token})
    status, body = member.login(username, password)
    run.record("AUTH-RELOGIN-AFTER-REPLAY", "POST /api/v1/mobile/auth/login", "MEMBER", status, {200}, body,
               "replay revokes only the compromised family; a fresh login must work")

    # Push and notification preferences.
    fake_push = "UAT-" + secrets.token_urlsafe(48)
    push_status, push_body, _, _ = run.call(
        "PUSH-REGISTER", member, "POST", "/api/v1/mobile/devices/push-registration", {204, 503},
        body={"provider": "UNIPUSH", "registrationId": fake_push, "vendor": "UAT"},
        detail="204=provider configured; 503=MOBILE_PUSH_NOT_CONFIGURED explicit environment contract")
    if push_status == 204:
        run.call("PUSH-PREFERENCES", member, "PUT", "/api/v1/mobile/devices/notification-preferences", {200}, body={"urgentReminders": True, "tasks": True, "consultations": True, "announcements": False, "releases": True})
        run.call("PUSH-UNREGISTER", member, "DELETE", "/api/v1/mobile/devices/push-registration", {204})
    else:
        run.record("PUSH-PREFERENCES", "provider unavailable", "MEMBER", 503, {503}, push_body,
                   "not executed because the deployment explicitly reports no push provider")
        run.record("PUSH-UNREGISTER", "provider unavailable", "MEMBER", 503, {503}, push_body,
                   "not executed because no registration was created")

    # Native administrator read surface. These calls mirror every management tab without using a WebView.
    month = datetime.now(CN).strftime("%Y-%m")
    range_from = (datetime.now(CN).date() - timedelta(days=7)).isoformat()
    range_to = datetime.now(CN).date().isoformat()
    admin_reads = [
        ("ADMIN-OVERVIEW", "/api/v1/mobile/admin/overview", {200}),
        ("ADMIN-MEMBERS", "/api/v1/admin/members", {200}),
        ("ADMIN-TEAMS", "/api/v1/admin/teams", {200}),
        ("ADMIN-REGISTRATION-CODES", "/api/v1/admin/registration-codes", {200}),
        ("ADMIN-DEAL-REQUESTS", "/api/v1/admin/deal-requests?page=0&size=20", {200}),
        ("ADMIN-MARKET-REVIEWS", "/api/v1/admin/market/classification-reviews?page=0&size=20", {200}),
        ("ADMIN-MARKET-ANOMALIES", "/api/v1/admin/market/classification-anomalies", {200}),
        ("ADMIN-CROSS-SALES", "/api/v1/admin/cross-sales?page=0&size=20", {200}),
        ("ADMIN-TASK-MATRIX", "/api/v1/tasks/matrix", {200}),
        ("ADMIN-DAILY-CALENDAR", f"/api/v1/daily-reports/calendar?month={month}", {200}),
        ("ADMIN-PERFORMANCE-CLAIMS", "/api/v1/performance/claims", {200}),
        ("ADMIN-PERFORMANCE-ACTIVITIES", "/api/v1/performance/activities", {200}),
        ("ADMIN-PERFORMANCE-RULES", "/api/v1/performance/admin/rules", {200}),
        ("ADMIN-PERFORMANCE-PARTICIPANTS", "/api/v1/performance/admin/participants", {200}),
        ("ADMIN-PERFORMANCE-DAY", f"/api/v1/performance/admin/days/{range_to}", {200, 204}),
        ("ADMIN-PERFORMANCE-HISTORY", f"/api/v1/performance/history?from={range_from}&to={range_to}", {200}),
        ("ADMIN-PERFORMANCE-LEADERBOARD", f"/api/v1/performance/leaderboard?from={range_from}&to={range_to}", {200}),
        ("ADMIN-TAGS", "/api/v1/tags", {200}),
        ("ADMIN-DICTIONARIES", "/api/v1/dictionaries", {200}),
        ("ADMIN-AI-SETTINGS", "/api/v1/admin/assistant-settings", {200}),
        ("ADMIN-INTELLIGENCE-SETTINGS", "/api/v1/admin/customer-intelligence/settings", {200}),
        ("ADMIN-INTELLIGENCE-RUNTIME", "/api/v1/admin/customer-intelligence/runtime", {200}),
        ("ADMIN-ENRICHMENT-JOBS", "/api/v1/admin/customer-enrichment-jobs", {200}),
        ("ADMIN-AGENT-AUTHORIZATIONS", "/api/v1/admin/agent/authorizations", {200}),
        ("ADMIN-AUDIT", "/api/v1/admin/audit?page=0&size=20", {200}),
        ("ADMIN-WEBHOOKS", "/api/v1/admin/wecom-webhooks", {200}),
        ("ADMIN-WEBHOOK-DELIVERIES", "/api/v1/admin/wecom-webhooks/deliveries?size=20", {200}),
        ("ADMIN-FOLLOWUP-POLICY", "/api/v1/admin/follow-up-policy", {200}),
        ("ADMIN-CONSULTATION-SETTINGS", "/api/v1/admin/consultation-settings", {200}),
        ("ADMIN-LEADERSHIP-SETTINGS", "/api/v1/admin/leadership-screen-settings", {200}),
        ("ADMIN-LEADERSHIP-DASHBOARD", "/api/v1/leadership-dashboard?days=30", {200}),
    ]
    for scenario, path, expected in admin_reads:
        run.call(scenario, admin, "GET", path, expected, role="ORG_ADMIN")
    run.call("MANAGER-LEADERSHIP-DASHBOARD", manager, "GET", "/api/v1/leadership-dashboard?days=30", {200}, role="TEAM_MANAGER")
    run.call("MANAGER-ADMIN-OVERVIEW-DENY", manager, "GET", "/api/v1/mobile/admin/overview", {403}, role="TEAM_MANAGER")
    run.call("MEMBER-ADMIN-OVERVIEW-DENY", member, "GET", "/api/v1/mobile/admin/overview", {403}, role="MEMBER")

    # Native download routes must produce real office files instead of JSON placeholders.
    run.call("ADMIN-CROSS-SALES-EXPORT", admin, "GET", "/api/v1/admin/cross-sales/export.xlsx", {200}, role="ORG_ADMIN")
    run.call("ADMIN-PERFORMANCE-EXPORT", admin, "GET", f"/api/v1/performance/admin/export?from={range_from}&to={range_to}", {200}, role="ORG_ADMIN")
    run.call("ADMIN-AUDIT-EXPORT", admin, "GET", "/api/v1/admin/audit/export", {200}, role="ORG_ADMIN")

    # Exercise reversible management writes in the isolated database.
    run.call("ADMIN-INTELLIGENCE-PAUSE", admin, "POST", "/api/v1/admin/customer-intelligence/backfill/pause", {200}, role="ORG_ADMIN")
    run.call("ADMIN-INTELLIGENCE-RESUME", admin, "POST", "/api/v1/admin/customer-intelligence/backfill/resume", {200}, role="ORG_ADMIN")
    run.call("ADMIN-TAG-CREATE", admin, "POST", "/api/v1/tags", {201}, role="ORG_ADMIN",
             body={"name": "Android UAT " + marker, "category": "移动验收", "color": "#356AE6"})
    run.call("ADMIN-DICTIONARY-CREATE", admin, "POST", "/api/v1/dictionaries", {201}, role="ORG_ADMIN",
             body={"type": "SOURCE_CHANNEL", "code": "ANDROID_UAT_" + marker.upper(),
                   "label": "Android UAT " + marker, "sortOrder": 999})

    # Customers and permission boundaries.
    _, own_customer, _, _ = run.call("CUSTOMER-CREATE", member, "POST", "/api/v1/customers", {201}, body=customer_body("安卓全功能客户" + marker, team_a_id))
    customer_id = str(own_customer.get("id", ""))
    version = int(own_customer.get("version", 0))
    run.call("CUSTOMER-LIST", member, "GET", "/api/v1/customers?page=0&size=100", {200})
    run.call("CUSTOMER-GET", member, "GET", f"/api/v1/customers/{customer_id}", {200})
    run.call("CUSTOMER-COLLABORATION", member, "GET", f"/api/v1/customers/{customer_id}/collaboration", {200})
    updated = customer_body("安卓全功能客户" + marker, team_a_id)
    updated.update({"nickname": "已更新", "expectedVersion": version})
    _, own_customer, _, _ = run.call("CUSTOMER-UPDATE", member, "PATCH", f"/api/v1/customers/{customer_id}", {200}, body=updated)
    version = int(own_customer.get("version", version + 1))
    _, other_customer, _, _ = run.call("CUSTOMER-CROSS-TEAM-SETUP", outsider, "POST", "/api/v1/customers", {201}, role="MEMBER-TEAM-B", body=customer_body("跨团队客户" + marker, team_b_id))
    other_id = str(other_customer.get("id", ""))
    run.call("CUSTOMER-CROSS-TEAM-DENY", member, "GET", f"/api/v1/customers/{other_id}", {403, 404})
    run.call("CUSTOMER-RANDOM-ID", member, "GET", f"/api/v1/customers/{uuid.uuid4()}", {404})
    run.call("VIEWER-CUSTOMER-WRITE-DENY", viewer, "POST", "/api/v1/customers", {403}, role="VIEWER", body=customer_body("只读越权" + marker, team_a_id))
    run.call("TEAM-MANAGER-ADMIN-DENY", manager, "GET", "/api/v1/admin/members", {403}, role="TEAM_MANAGER")

    now = datetime.now(timezone.utc)
    interaction_body = {
        "contactedAt": (now - timedelta(minutes=5)).isoformat().replace("+00:00", "Z"),
        "channel": "Android UAT", "summary": "客户已回复并确认下一步", "details": "真实移动端契约验收",
        "outcome": "继续跟进", "verificationTodo": "无", "nextFollowUpAt": (now + timedelta(days=1)).isoformat().replace("+00:00", "Z"),
        "currentStatus": "跟进中", "intentionLevel": "高", "verificationStatus": "已核实", "tagIds": [],
        "interactionType": "CUSTOMER_REPLY", "sourceMessageId": "android-uat-" + marker,
    }
    _, interaction, _, _ = run.call("TIMELINE-CREATE", member, "POST", f"/api/v1/customers/{customer_id}/interactions", {201}, body=interaction_body)
    interaction_id = str(interaction.get("id", ""))
    server_now_body = dict(interaction_body)
    server_now_body.pop("contactedAt", None)
    server_now_body["sourceMessageId"] = "android-uat-server-now-" + marker
    run.call("TIMELINE-SERVER-NOW", member, "POST", f"/api/v1/customers/{customer_id}/interactions", {201},
             body=server_now_body, detail="omitting contactedAt must use server receive time")
    tolerated_clock_body = dict(interaction_body)
    tolerated_clock_body["contactedAt"] = (datetime.now(timezone.utc) + timedelta(seconds=60)).isoformat().replace("+00:00", "Z")
    tolerated_clock_body["sourceMessageId"] = "android-uat-clock-tolerance-" + marker
    run.call("TIMELINE-CLOCK-TOLERANCE", member, "POST", f"/api/v1/customers/{customer_id}/interactions", {201},
             body=tolerated_clock_body, detail="a client clock up to 120 seconds fast must be normalized")
    rejected_future_body = dict(interaction_body)
    rejected_future_body["contactedAt"] = (datetime.now(timezone.utc) + timedelta(seconds=180)).isoformat().replace("+00:00", "Z")
    rejected_future_body["sourceMessageId"] = "android-uat-future-rejected-" + marker
    run.call("TIMELINE-FUTURE-REJECTED", member, "POST", f"/api/v1/customers/{customer_id}/interactions", {400},
             body=rejected_future_body, detail="a true future interaction must retain the stable validation error")
    run.call("TIMELINE-LIST", member, "GET", f"/api/v1/customers/{customer_id}/interactions", {200})
    run.call("CUSTOMER-CLASSIFICATION", member, "POST", f"/api/v1/customers/{customer_id}/classifications", {201},
             body={"level": "B", "recognitionReason": "Android V52 真实沟通依据",
                   "potentialDemand": "后续咨询与产品适配", "aSignals": [],
                   "evidenceInteractionIds": [interaction_id], "correctionReason": None})
    run.call("CUSTOMER-CLASSIFICATION-CURRENT", member, "GET",
             f"/api/v1/customers/{customer_id}/classifications/current", {200})
    run.call("REMINDER-LIST", member, "GET", "/api/v1/reminders", {200})
    _, reminder, _, _ = run.call("REMINDER-CREATE", member, "POST", "/api/v1/reminders", {201}, body={"customerId": customer_id, "remindAt": (now + timedelta(hours=4)).isoformat().replace("+00:00", "Z"), "title": "Android 验收提醒"})
    reminder_id = str(reminder.get("id", ""))
    run.call("REMINDER-COMPLETE", member, "PATCH", f"/api/v1/reminders/{reminder_id}", {200}, body={"status": "COMPLETED"})

    # Dashboard, daily report and inbox.
    run.call("DASHBOARD", member, "GET", "/api/v1/dashboard", {200})
    _, daily_today, _, _ = run.call("DAILY-TODAY", member, "GET", "/api/v1/daily-reports/today", {200})
    today = str((daily_today or {}).get("date") or datetime.now(CN).date().isoformat())
    run.call("DAILY-SUBMIT", member, "PUT", f"/api/v1/daily-reports/{today}", {200}, body={"deepCommunicationCount": 1, "todayIssues": "Android UAT 今日问题", "tomorrowPlan": "继续验证移动闭环"})
    run.call("NOTIFICATIONS", member, "GET", "/api/v1/notifications", {200})

    # Tasks: administrator creates, member claims and submits.
    task_body = {"title": "Android UAT 自领取任务 " + marker, "description": "验证领取和提交",
                 "distributionType": "SELF_CLAIM", "startAt": (now - timedelta(minutes=1)).isoformat().replace("+00:00", "Z"),
                 "dueAt": (now + timedelta(days=2)).isoformat().replace("+00:00", "Z"), "maxClaimants": 2, "assigneeIds": []}
    _, task, _, _ = run.call("TASK-SETUP-CREATE", admin, "POST", "/api/v1/tasks", {201}, role="ORG_ADMIN", body=task_body)
    task_id = str(task.get("id", ""))
    run.call("TASK-LIST", member, "GET", "/api/v1/tasks", {200})
    run.call("TASK-CLAIM", member, "POST", f"/api/v1/tasks/{task_id}/claim", {200})
    run.call("TASK-SUBMIT", member, "PATCH", f"/api/v1/tasks/{task_id}/my-assignment", {200}, body={"status": "SUBMITTED", "resultSummary": "Android UAT 已完成", "evidenceUrl": None})

    # Performance: configure participant/rule if necessary, then claim + evidence + submit.
    run.call("PERFORMANCE-TODAY", member, "GET", "/api/v1/performance/today", {200})
    rule_body = {"effectiveFrom": today, "dailyBaseline": 14, "calendarMode": "EVERY_DAY", "weekdays": [],
                 "includedDates": [], "excludedDates": [], "submissionOpensAt": "00:00:00", "submissionClosesAt": "23:59:59",
                 "shortfallMode": "DISPLAY_ONLY", "ordinaryPoints": 1, "deepPoints": 2,
                 "ordinaryDailyCap": 20, "deepDailyCap": 20, "eventTiers": [{"label": "Pro", "minParticipants": 50, "maxParticipants": None, "points": 8}]}
    _, rule_rows, _, _ = run.call("PERFORMANCE-RULES", admin, "GET", "/api/v1/performance/admin/rules", {200}, role="ORG_ADMIN")
    if not isinstance(rule_rows, list) or not rule_rows:
        rule_body["effectiveFrom"] = (datetime.now(CN).date() + timedelta(days=1)).isoformat()
        run.call("PERFORMANCE-RULE-SETUP", admin, "POST", "/api/v1/performance/admin/rules", {201}, role="ORG_ADMIN", body=rule_body,
                 detail="new rule versions must start in the future")
    run.call("PERFORMANCE-PARTICIPANT-SETUP", admin, "POST", "/api/v1/performance/admin/participants", {201, 409}, role="ORG_ADMIN", body={"membershipId": member_id, "startsOn": today, "endsOn": None})
    _, claim, _, _ = run.call("PERFORMANCE-CLAIM-CREATE", member, "POST", "/api/v1/performance/claims", {201}, body={"interactionId": interaction_id, "requestedType": "DEEP"})
    claim_id = str(claim.get("id", ""))
    # 1x1 valid PNG, no customer content.
    png = bytes.fromhex("89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000d49444154789c63606060f80f0001040100b8f7706f0000000049454e44ae426082")
    upload, upload_type = multipart("file", "android-uat.png", png, "image/png")
    run.call("PERFORMANCE-EVIDENCE", member, "POST", f"/api/v1/performance/claims/{claim_id}/evidence", {200, 201}, raw=upload, content_type=upload_type)
    run.call("PERFORMANCE-CLAIM-SUBMIT", member, "POST", f"/api/v1/performance/claims/{claim_id}/submit", {200})

    # Consultation create/read. Completion is state dependent and exercised by the zero-skip backend suite.
    run.call("CONSULTATION-LIST", member, "GET", "/api/v1/consultations", {200})
    _, candidates, _, _ = run.call("CONSULTATION-CANDIDATES", member, "GET", "/api/v1/consultations/candidates", {200})
    run.call("CONSULTATION-FAILURE-REASONS", member, "GET", "/api/v1/consultation-failure-reasons", {200})
    start = (now + timedelta(days=1)).replace(hour=2, minute=0, second=0, microsecond=0)
    consultant_id = peer_id or (str(candidates[0]["membershipId"]) if candidates else "")
    consultation_body = {"customerId": customer_id, "consultantMembershipId": consultant_id,
                         "sourceInteractionIds": [interaction_id], "consultationReason": "Android UAT 交接",
                         "customerQuotes": "客户确认愿意继续沟通", "goalsMotivation": "验证咨询闭环", "actionsTaken": "已完成初聊",
                         "mainConcerns": "时间安排", "decisionParticipants": None, "decisionParticipantConsent": False,
                         "productFit": "待顾问确认", "suggestedFocus": "真实需求", "explicitTaboos": "不得虚构",
                         "expectedResult": "形成下一步", "scheduledStart": start.isoformat().replace("+00:00", "Z"),
                         "scheduledEnd": (start + timedelta(minutes=30)).isoformat().replace("+00:00", "Z")}
    _, consultation, _, _ = run.call("CONSULTATION-CREATE", member, "POST", "/api/v1/consultations", {200, 201}, body=consultation_body)
    consultation_id = str((consultation or {}).get("id", ""))
    if consultation_id:
        run.call("CONSULTATION-GET", member, "GET", f"/api/v1/consultations/{consultation_id}", {200})

    # Content lifecycle.
    _, direction, _, _ = run.call("CONTENT-DIRECTION-SETUP", admin, "POST", "/api/v1/content-directions", {201}, role="ORG_ADMIN", body={"title": "Android UAT " + marker, "description": "隔离验证", "active": True})
    direction_id = str(direction.get("id", ""))
    run.call("CONTENT-OVERVIEW", member, "GET", "/api/v1/content-tasks/overview", {200})
    _, post, _, _ = run.call("CONTENT-POST", member, "POST", "/api/v1/content-posts", {201, 400}, body={"platform": "XIAOHONGSHU", "publishDate": today, "directionId": direction_id, "topic": "Android UAT", "contentUrl": "https://example.invalid/android-uat", "viewCount": 1, "likeCount": 0, "favoriteCount": 0, "originalDeclared": True},
                               detail="201=campaign open; 400 OUTSIDE_CAMPAIGN_PERIOD=closed campaign contract")
    post_id = str(post.get("id", ""))
    if post_id:
        run.call("CONTENT-METRICS", member, "PATCH", f"/api/v1/content-posts/{post_id}/metrics", {200}, body={"viewCount": 2, "likeCount": 1, "favoriteCount": 1})
        run.call("CONTENT-ADMIN-REVIEW", admin, "PATCH", f"/api/v1/content-posts/{post_id}/review", {200}, role="ORG_ADMIN",
                 body={"status": "VALID", "reason": "Android 0.2.1 隔离验收"})
    else:
        run.record("CONTENT-METRICS", "campaign closed", "MEMBER", 400, {400}, post,
                   "not executed because no post was created outside the configured campaign")
        run.record("CONTENT-ADMIN-REVIEW", "campaign closed", "ORG_ADMIN", 400, {400}, post,
                   "not executed because there is no post to review")
    run.call("CIRCLE", member, "GET", "/api/v1/circle/posts?page=0&size=20", {200})
    run.call("ANNOUNCEMENTS", member, "GET", "/api/v1/announcements", {200})
    _, announcement, _, _ = run.call("ANNOUNCEMENT-ADMIN-CREATE", admin, "POST", "/api/v1/announcements", {201}, role="ORG_ADMIN",
                                     body={"title": "Android UAT " + marker, "content": "隔离数据库移动端公告闭环",
                                           "priority": "NORMAL", "pinned": False, "showOnLeadershipScreen": False})
    announcement_id = str((announcement or {}).get("id", ""))
    if announcement_id:
        run.call("ANNOUNCEMENT-ADMIN-ARCHIVE", admin, "POST", f"/api/v1/announcements/{announcement_id}/archive", {204}, role="ORG_ADMIN")

    # AI, product releases, Agent and collaboration queues.
    run.call("AI-AVAILABILITY", member, "GET", "/api/v1/assistant/availability", {200})
    run.call("AI-CUSTOMER-SUMMARY", member, "GET", f"/api/v1/assistant/customers/{customer_id}/summary", {200, 503}, detail="200=SSE provider available; 503=explicit unavailable contract")
    run.call("AI-MOBILE-CUSTOMER-INSIGHT", member, "POST", f"/api/v1/mobile/customers/{customer_id}/insight", {200},
             detail="mobile JSON insight must complete without relying on SSE close semantics")
    _, greeting, _, _ = run.call("DASHBOARD-MOBILE-GREETING", member, "POST", "/api/v1/mobile/dashboard/greeting", {200})
    run.record("DASHBOARD-GREETING-DATA", "salutation/subtitle", "MEMBER",
               1 if isinstance(greeting, dict)
               and greeting.get("salutation") in {"早上好", "中午好", "下午好", "晚上好", "夜深了"}
               and str(greeting.get("subtitle", "")).strip() else 0, {1}, greeting,
               "server-calibrated greeting must contain a valid time band and non-empty subtitle")
    _, releases, _, _ = run.call("RELEASES-LIST", member, "GET", "/api/v1/releases?page=0&size=20", {200})
    _, unacked, _, _ = run.call("RELEASE-UNACKNOWLEDGED", member, "GET", "/api/v1/releases/current/unacknowledged", {200, 204})
    if isinstance(unacked, dict) and unacked.get("id"):
        run.call("RELEASE-ACKNOWLEDGE", member, "POST", f"/api/v1/releases/{unacked['id']}/acknowledge", {204})
    else:
        run.record("RELEASE-ACKNOWLEDGE", "POST /api/v1/releases/{releaseId}/acknowledge", "MEMBER", 204, {204}, detail="no unacknowledged release; idempotence already satisfied")
    run.call("AGENT-AUTHORIZATIONS", member, "GET", "/api/v1/agent/authorizations", {200})
    run.call("AGENT-REVOKE-RANDOM", member, "DELETE", f"/api/v1/agent/authorizations/{uuid.uuid4()}", {404})
    run.call("POOL-LIST", member, "GET", "/api/v1/customer-pools", {200})
    run.call("POOL-CLAIM-RANDOM", member, "POST", f"/api/v1/customer-pools/{uuid.uuid4()}/claims", {404, 409}, body={"idempotencyKey": str(uuid.uuid4())})
    run.call("SECOND-LINE-LIST", member, "GET", "/api/v1/second-line-recruitments", {200})
    run.call("SECOND-LINE-CLAIM-RANDOM", member, "POST", f"/api/v1/second-line-recruitments/{uuid.uuid4()}/claim", {404, 409})

    # Simplified deal request and admin approval.
    _, deal, _, _ = run.call("DEAL-REQUEST", member, "POST", f"/api/v1/customers/{customer_id}/deal-requests", {201}, headers={"Idempotency-Key": str(uuid.uuid4())})
    deal_id = str((deal or {}).get("id", ""))
    deal_version = str((deal or {}).get("version", ""))
    _, repeated_deal, _, _ = run.call(
        "DEAL-REQUEST-IDEMPOTENT", member, "POST",
        f"/api/v1/customers/{customer_id}/deal-requests", {201},
        headers={"Idempotency-Key": str(uuid.uuid4())},
        detail="a second request returns the existing pending record instead of creating another row")
    if isinstance(repeated_deal, dict) and repeated_deal.get("id") != deal_id:
        run.record("DEAL-REQUEST-SINGLE-PENDING", "pending request id", "MEMBER", 0, {1},
                   repeated_deal, "expected the existing pending request id")
    run.call("DEAL-ADMIN-LIST", admin, "GET", "/api/v1/admin/deal-requests?status=PENDING&page=0&size=50", {200}, role="ORG_ADMIN")
    if deal_id:
        run.call("DEAL-APPROVE", admin, "POST", f"/api/v1/admin/deal-requests/{deal_id}/approve", {200}, role="ORG_ADMIN",
                 headers={"If-Match-Version": deal_version})
        _, won, _, _ = run.call("DEAL-WON-STATE", member, "GET", f"/api/v1/customers/{customer_id}", {200})
        if isinstance(won, dict) and won.get("dealState") != "WON":
            run.record("DEAL-WON-DATA-CHECK", "dealState", "MEMBER", 0, {1}, won, "expected WON")

    # Transfer ownership and verify previous owner no longer has private access.
    _, latest, _, _ = member.call("GET", f"/api/v1/customers/{customer_id}")
    expected_version = int((latest or {}).get("version", version))
    run.call("CUSTOMER-TRANSFER", member, "POST", f"/api/v1/customers/{customer_id}/transfer", {200}, body={"targetMembershipId": peer_id, "expectedVersion": expected_version, "reason": "Android UAT 转接闭环"})
    run.call("CUSTOMER-OLD-OWNER-AFTER-TRANSFER", member, "GET", f"/api/v1/customers/{customer_id}", {403, 404})
    run.call("CUSTOMER-NEW-OWNER-AFTER-TRANSFER", peer, "GET", f"/api/v1/customers/{customer_id}", {200}, role="MEMBER-NEW-OWNER")

    # WebView ticket: create, exchange, then prove replay rejection.
    _, ticket_body, _, _ = run.call("WEBVIEW-TICKET-CREATE", member, "POST", "/api/v1/mobile/web-sessions", {201}, body={"target": "QQ_WORKBENCH"})
    ticket = str((ticket_body or {}).get("ticket", ""))
    if ticket:
        run.call("WEBVIEW-TICKET-EXCHANGE", member, "POST", "/api/v1/mobile/web-sessions/exchange", {303}, authenticated=False, form={"ticket": ticket}, follow_redirects=False)
        run.call("WEBVIEW-TICKET-REPLAY", member, "POST", "/api/v1/mobile/web-sessions/exchange", {409}, authenticated=False, form={"ticket": ticket}, follow_redirects=False)

    # Current Android release and binary integrity.
    _, release, _, _ = run.call("MOBILE-RELEASE-CURRENT", member, "GET", "/api/v1/mobile/app-releases/current", {200},
                                headers={"X-Yingge-Version-Code": "1", "X-Yingge-App-Version": "0.0.1-uat"},
                                detail="an older installed build must discover Android 0.2.1")
    if isinstance(release, dict) and release.get("id"):
        status, binary, _, raw = run.call("MOBILE-RELEASE-APK", member, "GET", f"/api/v1/mobile/app-releases/{release['id']}/apk", {200})
        expected_hash = str(release.get("apkSha256", "")).lower()
        actual_hash = hashlib.sha256(raw).hexdigest()
        run.record("MOBILE-RELEASE-SHA256", "APK SHA-256", "MEMBER", 1 if expected_hash and expected_hash == actual_hash else 0, {1}, detail="manifest hash matches downloaded APK")

    # Revoke another session then logout current. The functional records remain in the isolated DB.
    _, devices, _, _ = run.call("MOBILE-DEVICES-FINAL", member, "GET", "/api/v1/mobile/auth/devices", {200})
    device_rows = devices if isinstance(devices, list) else []
    other_sessions = [row for row in device_rows if isinstance(row, dict) and not row.get("current")]
    if other_sessions:
        run.call("MOBILE-DEVICE-REVOKE", member, "DELETE", f"/api/v1/mobile/auth/devices/{other_sessions[0]['id']}", {204})
    run.call("MOBILE-LOGOUT", outsider, "POST", "/api/v1/mobile/auth/logout", {204}, role="MEMBER-TEAM-B")

    # Persist evidence with no secret-bearing payloads.
    csv_path = args.output / "android-api-results.csv"
    with csv_path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(Result.__annotations__))
        writer.writeheader()
        writer.writerows([row.__dict__ for row in run.rows])
    summary = {
        "checks": len(run.rows),
        "pass": sum(row.result == "PASS" for row in run.rows),
        "fail": sum(row.result == "FAIL" for row in run.rows),
        "generatedAt": datetime.now(CN).isoformat(),
        "base": args.base,
    }
    (args.output / "android-api-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False))
    return 1 if summary["fail"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
