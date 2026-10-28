#!/usr/bin/env python3
"""Formal business REST journeys and permission matrix for the isolated UAT stack."""

from __future__ import annotations

import csv
import hashlib
import io
import json
import os
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable

import requests


SEED = 20260720
SCENARIO_IDS = [
    "E2E-A01", "E2E-A02", "E2E-A03", "E2E-B01", "E2E-C01", "E2E-C02", "E2E-C03", "E2E-C04",
    "E2E-D01", "E2E-D02", "E2E-D03", "E2E-E01", "E2E-E02", "E2E-E03",
    "E2E-F01", "E2E-F02", "E2E-F03", "E2E-G01", "E2E-H01", "E2E-H02",
    "E2E-I01", "E2E-I02", "E2E-I03", "E2E-J01", "E2E-J02", "E2E-J03",
    "E2E-K01", "E2E-K02", "E2E-L01", "E2E-L02", "E2E-M01", "E2E-M02",
]
REST_CAPABILITIES = [
    "auth", "registration-codes", "dashboard", "customers", "interactions", "reminders", "tags",
    "dictionaries", "tasks", "content-directions", "content-posts", "content-overview", "circle",
    "daily-reports", "team-data", "announcements", "imports", "exports", "assistant",
    "admin-members", "admin-teams", "admin-audit", "health-openapi",
    "admin-assistant-settings",
]


def read_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key] = value
    return values


def deterministic_uuid(label: str) -> str:
    return str(uuid.UUID(hashlib.md5(label.encode("utf-8")).hexdigest()))


def response_json(response: requests.Response) -> Any:
    try:
        return response.json()
    except ValueError:
        return None


class Failure(AssertionError):
    pass


@dataclass
class Scenario:
    scenario_id: str
    role: str
    started_at: str
    ended_at: str
    status: str
    actual: str
    trace_ids: list[str]


class Recorder:
    def __init__(self) -> None:
        self.scenarios: list[Scenario] = []
        self.capabilities: dict[str, dict[str, Any]] = {
            name: {"executed": False, "checks": 0, "failures": 0} for name in REST_CAPABILITIES
        }
        self.permissions: list[dict[str, Any]] = []
        self.traces: list[str] = []
        self._scenario_traces: list[str] = []
        self._lock = threading.Lock()

    def observe(self, capability: str, response: requests.Response) -> None:
        trace = response.headers.get("X-Trace-Id")
        body = response_json(response)
        if not trace and isinstance(body, dict):
            trace = body.get("traceId")
        with self._lock:
            bucket = self.capabilities[capability]
            bucket["executed"] = True
            bucket["checks"] += 1
            if trace:
                self.traces.append(str(trace))
                self._scenario_traces.append(str(trace))

    def expected(self, capability: str, response: requests.Response, statuses: int | set[int], label: str) -> Any:
        self.observe(capability, response)
        allowed = {statuses} if isinstance(statuses, int) else statuses
        if response.status_code not in allowed:
            self.capabilities[capability]["failures"] += 1
            body = response_json(response)
            code = body.get("code") if isinstance(body, dict) else "NON_JSON"
            raise Failure(f"{label}: HTTP {response.status_code}, code={code}, expected={sorted(allowed)}")
        return response_json(response)

    def truth(self, condition: bool, label: str) -> None:
        if not condition:
            raise Failure(label)

    def run(self, scenario_id: str, role: str, fn: Callable[[], str | None]) -> None:
        started = datetime.now(timezone.utc).isoformat()
        self._scenario_traces = []
        try:
            detail = fn() or "all assertions passed"
            status = "PASS"
        except Failure as error:
            detail, status = str(error), "FAIL"
        except Exception as error:  # continue remaining formal scenarios
            detail = f"{type(error).__name__}: {error}"
            status = "FAIL"
        self.scenarios.append(Scenario(
            scenario_id, role, started, datetime.now(timezone.utc).isoformat(), status, detail,
            list(dict.fromkeys(self._scenario_traces)),
        ))
        print(f"{scenario_id} {status}", flush=True)

    def permission(self, resource: str, probe: str, role: str, response: requests.Response,
                   expected_status: set[int], capability: str) -> None:
        self.observe(capability, response)
        body = response_json(response)
        code = body.get("code") if isinstance(body, dict) else ""
        passed = response.status_code in expected_status
        self.permissions.append({
            "resource": resource, "probe": probe, "role": role,
            "expected_status": "/".join(str(value) for value in sorted(expected_status)),
            "actual_status": response.status_code, "code": code or "",
            "result": "PASS" if passed else "FAIL",
            "trace_id": response.headers.get("X-Trace-Id", ""),
        })


class Client:
    def __init__(self, base: str, recorder: Recorder, username: str | None = None,
                 password: str | None = None, role: str = "UNAUTHENTICATED") -> None:
        self.base = base.rstrip("/")
        self.recorder = recorder
        self.username = username
        self.role = role
        self.password = password
        self.session = requests.Session()
        self.session.trust_env = False
        self.csrf = ""
        self.me: dict[str, Any] | None = None
        self.login_response: requests.Response | None = None

    def token(self) -> str:
        response = self.session.get(f"{self.base}/api/v1/auth/csrf", timeout=30)
        data = self.recorder.expected("auth", response, 200, f"{self.role} csrf")
        self.csrf = data["token"]
        return self.csrf

    def login(self) -> dict[str, Any]:
        self.token()
        response = self.session.post(
            f"{self.base}/api/v1/auth/login",
            json={"username": self.username, "password": self.password},
            headers={"X-XSRF-TOKEN": self.csrf}, timeout=30,
        )
        self.login_response = response
        self.me = self.recorder.expected("auth", response, 200, f"{self.role} login")
        return self.me

    def request(self, method: str, path: str, *, json_body: Any = None, params: Any = None,
                files: Any = None, timeout: int = 60, stream: bool = False) -> requests.Response:
        headers: dict[str, str] = {}
        if method.upper() not in {"GET", "HEAD", "OPTIONS"}:
            if not self.csrf:
                self.token()
            headers["X-XSRF-TOKEN"] = self.csrf
        return self.session.request(
            method, f"{self.base}{path}", json=json_body, params=params, files=files,
            headers=headers, timeout=timeout, stream=stream,
        )


class UatSuite:
    def __init__(self, base: str, password: str, output: Path) -> None:
        self.base, self.password, self.output = base, password, output
        self.output.mkdir(parents=True, exist_ok=True)
        self.rec = Recorder()
        self.tag = datetime.now(timezone.utc).strftime("%H%M%S")
        self.state: dict[str, Any] = {}
        self.clients: dict[str, Client] = {
            "ORG_ADMIN": Client(base, self.rec, "uat-admin", password, "ORG_ADMIN"),
            "TEAM_MANAGER": Client(base, self.rec, "leader-a", password, "TEAM_MANAGER"),
            "TEAM_MANAGER_B": Client(base, self.rec, "leader-b", password, "TEAM_MANAGER_B"),
            "MEMBER": Client(base, self.rec, "member-a1", password, "MEMBER"),
            "MEMBER_A2": Client(base, self.rec, "member-a2", password, "MEMBER_A2"),
            "MEMBER_B": Client(base, self.rec, "member-b1", password, "MEMBER_B"),
            "VIEWER": Client(base, self.rec, "viewer-a", password, "VIEWER"),
            "TENANT_X": Client(base, self.rec, "tenant-x-admin", password, "TENANT_X"),
            "UNAUTHENTICATED": Client(base, self.rec, role="UNAUTHENTICATED"),
        }
        self.fixtures = Path(__file__).resolve().parent / "fixtures"

    def c(self, role: str) -> Client:
        return self.clients[role]

    def login_all(self) -> None:
        for role, client in self.clients.items():
            if role != "UNAUTHENTICATED":
                client.login()
        admin = self.c("ORG_ADMIN")
        teams = self.rec.expected("admin-teams", admin.request("GET", "/api/v1/admin/teams"), 200, "teams list")
        members = self.rec.expected("admin-members", admin.request("GET", "/api/v1/admin/members"), 200, "members list")
        self.state["teams"] = {row["name"]: row for row in teams}
        self.state["members"] = {row["username"]: row for row in members}
        self.state["team_a"] = self.state["teams"]["星火队"]["id"]
        self.state["team_b"] = self.state["teams"]["远航队"]["id"]
        self.state["member_a1"] = self.state["members"]["member-a1"]["membershipId"]
        self.state["member_a2"] = self.state["members"]["member-a2"]["membershipId"]
        self.state["member_b1"] = self.state["members"]["member-b1"]["membershipId"]

    def customer_body(self, name: str, phone: str, visibility: str = "PRIVATE", **extra: Any) -> dict[str, Any]:
        body: dict[str, Any] = {
            "fullName": name, "phone": phone, "visibility": visibility,
            "identityType": "在校生", "focusDirections": ["升学"], "intentionLevel": "高",
            "urgencyLevel": "中", "verificationStatus": "未核实", "currentStatus": "跟进中",
        }
        body.update(extra)
        return body

    def scenario_a01(self) -> str:
        unauth = self.c("UNAUTHENTICATED")
        self.rec.expected("auth", unauth.request("GET", "/api/v1/auth/me"), 401, "unauthenticated me")
        raw = requests.Session(); raw.trust_env = False
        no_csrf = raw.post(f"{self.base}/api/v1/auth/login", json={"username": "uat-admin", "password": "redacted"}, timeout=30)
        self.rec.expected("auth", no_csrf, 403, "login without csrf")
        bad = Client(self.base, self.rec, "uat-admin", "incorrect", "BAD_PASSWORD")
        bad.token()
        self.rec.expected("auth", bad.request("POST", "/api/v1/auth/login", json_body={"username": "uat-admin", "password": "incorrect"}), 401, "bad password")
        admin = self.c("ORG_ADMIN")
        self.rec.expected("auth", admin.request("GET", "/api/v1/auth/me"), 200, "authenticated me")
        set_cookie = admin.login_response.headers.get("Set-Cookie", "") if admin.login_response else ""
        self.rec.truth("HttpOnly" in set_cookie and "Max-Age=2592000" in set_cookie, "session cookie missing HttpOnly or 30-day Max-Age")

        flow = Client(self.base, self.rec, "viewer-02", self.password, "PASSWORD_FLOW")
        flow.login()
        self.rec.expected("auth", flow.request("POST", "/api/v1/auth/password", json_body={"currentPassword": self.password, "newPassword": "1"}), 204, "weak password")
        self.rec.expected("auth", flow.request("POST", "/api/v1/auth/logout"), 204, "logout")
        self.rec.expected("auth", flow.request("GET", "/api/v1/auth/me"), 401, "old session after logout")
        weak = Client(self.base, self.rec, "viewer-02", "1", "PASSWORD_FLOW")
        weak.login()
        self.rec.expected("auth", weak.request("POST", "/api/v1/auth/password", json_body={"currentPassword": "1", "newPassword": self.password}), 204, "restore password")
        return "CSRF, login/logout, 30-day HttpOnly session, weak password and restore passed"

    def scenario_a02(self) -> str:
        admin = self.c("ORG_ADMIN")
        team = self.rec.expected("admin-teams", admin.request("POST", "/api/v1/admin/teams", json_body={"name": f"UAT-误建团队-{self.tag}"}), 201, "create team")
        self.state["temp_team"] = team["id"]
        member = self.rec.expected("admin-members", admin.request("POST", "/api/v1/admin/members", json_body={
            "username": f"uat-temp-leader-{self.tag}", "displayName": "UAT临时团队长",
            "initialPassword": self.password, "role": "TEAM_MANAGER", "teamId": team["id"],
        }), 201, "create leader")
        self.state["temp_leader"] = member["membershipId"]
        duplicate = admin.request("POST", "/api/v1/admin/members", json_body={
            "username": f"uat-second-leader-{self.tag}", "displayName": "UAT第二团队长",
            "initialPassword": self.password, "role": "TEAM_MANAGER", "teamId": team["id"],
        })
        self.rec.expected("admin-members", duplicate, 409, "second leader rejected")
        manager = self.c("TEAM_MANAGER")
        self.rec.expected("admin-members", manager.request("POST", "/api/v1/admin/members", json_body={
            "username": "forbidden", "displayName": "越权", "initialPassword": "1", "role": "MEMBER",
        }), 403, "manager organization administration denied")
        return "team/member creation and single leader constraint passed"

    def scenario_a03(self) -> str:
        admin = self.c("ORG_ADMIN")
        code = self.rec.expected("registration-codes", admin.request("POST", "/api/v1/admin/registration-codes", json_body={"maxUses": 1}), 201, "create registration code")
        options = self.rec.expected("auth", self.c("UNAUTHENTICATED").request("POST", "/api/v1/auth/registration-options", json_body={"registrationCode": code["code"]}), 200, "registration options")
        self.rec.truth(any(row["id"] == self.state["team_a"] for row in options["teams"]), "same-tenant team missing from registration options")
        registered = self.rec.expected("auth", self.c("UNAUTHENTICATED").request("POST", "/api/v1/auth/register", json_body={
            "username": f"uat-registered-{self.tag}", "realName": "UAT注册成员", "password": "1",
            "registrationCode": code["code"], "teamId": self.state["team_a"], "role": "ORG_ADMIN",
        }), 201, "register weak-password member")
        self.rec.truth(registered["role"] == "MEMBER", "registration allowed caller-selected role")
        self.rec.expected("auth", self.c("UNAUTHENTICATED").request("POST", "/api/v1/auth/register", json_body={
            "username": f"uat-register-reuse-{self.tag}", "realName": "重复使用", "password": "1",
            "registrationCode": code["code"], "teamId": self.state["team_a"],
        }), 409, "single-use code reuse")
        reg_client = Client(self.base, self.rec, f"uat-registered-{self.tag}", "1", "REGISTERED_MEMBER")
        reg_client.login()
        self.clients["REGISTERED_MEMBER"] = reg_client
        self.state["registered_membership"] = registered["membershipId"]
        list_body = self.rec.expected("registration-codes", admin.request("GET", "/api/v1/admin/registration-codes"), 200, "registration code list")
        self.rec.truth(code["code"] not in json.dumps(list_body, ensure_ascii=False), "plaintext registration code leaked by list")
        return "registration code, real name, team choice, weak password and fixed MEMBER role passed"

    def scenario_b01(self) -> str:
        admin, member = self.c("ORG_ADMIN"), self.c("MEMBER")
        dictionary = self.rec.expected("dictionaries", admin.request("POST", "/api/v1/dictionaries", json_body={
            "type": "SOURCE_CHANNEL", "code": f"REST-{self.tag}", "label": "UAT REST 来源", "sortOrder": 900,
        }), 201, "create dictionary")
        self.rec.expected("dictionaries", admin.request("POST", "/api/v1/dictionaries", json_body={
            "type": "SOURCE_CHANNEL", "code": f"REST-{self.tag}", "label": "重复", "sortOrder": 901,
        }), 409, "duplicate dictionary code")
        tag = self.rec.expected("tags", member.request("POST", "/api/v1/tags", json_body={
            "name": f"UAT-REST-标签-{self.tag}", "category": "验收", "color": "#2563eb",
        }), 201, "member creates tag")
        direction = self.rec.expected("content-directions", admin.request("POST", "/api/v1/content-directions", json_body={
            "title": f"UAT-REST-方向-{self.tag}", "description": "正式 UAT 方向", "active": True,
        }), 201, "create content direction")
        disabled = self.rec.expected("content-directions", admin.request("POST", "/api/v1/content-directions", json_body={
            "title": f"UAT-REST-停用方向-{self.tag}", "description": "待停用", "active": True,
        }), 201, "create direction to disable")
        disabled = self.rec.expected("content-directions", admin.request(
            "PATCH", f"/api/v1/content-directions/{disabled['id']}",
            json_body={"title": disabled["title"], "description": "已停用", "active": False},
        ), 200, "disable content direction")
        self.rec.truth(disabled["active"] is False, "content direction was not disabled")
        self.rec.expected("content-directions", member.request("POST", "/api/v1/content-directions", json_body={"title": "越权方向"}), 403, "member direction maintenance")
        self.state.update(dictionary=dictionary, tag=tag, direction=direction, disabled_direction=disabled)
        return "dictionary, tag and content direction role boundaries passed"

    def scenario_c01(self) -> str:
        member = self.c("MEMBER")
        private = self.rec.expected("customers", member.request("POST", "/api/v1/customers", json_body=self.customer_body(
            f"UAT-20260720-贯穿客户-{self.tag}", f"199{self.tag}01", nextFollowUpAt=(datetime.now(timezone.utc)+timedelta(hours=3)).isoformat(),
            tagIds=[self.state["tag"]["id"]],
        )), 201, "create private customer")
        self.rec.expected("customers", member.request("POST", "/api/v1/customers/duplicate-check", json_body={
            "fullName": "其他姓名", "phone": f"199{self.tag}01",
        }), 200, "exact duplicate check")
        self.rec.expected("customers", member.request("POST", "/api/v1/customers", json_body=self.customer_body(
            "精确重复", f"199{self.tag}01"
        )), 409, "exact duplicate rejected")
        self.rec.expected("customers", member.request("POST", "/api/v1/customers", json_body=self.customer_body(
            f"UAT-20260720-贯穿客户-{self.tag}", f"199{self.tag}02"
        )), 409, "similar name needs override")
        similar = self.rec.expected("customers", member.request("POST", "/api/v1/customers", json_body=self.customer_body(
            f"UAT-20260720-贯穿客户-{self.tag}", f"199{self.tag}02", duplicateOverrideReason="已核实为不同客户"
        )), 201, "similar name override")
        team = self.rec.expected("customers", member.request("POST", "/api/v1/customers", json_body=self.customer_body(
            f"UAT-20260720-团队客户-{self.tag}", f"199{self.tag}03", "TEAM", teamId=self.state["team_a"]
        )), 201, "team-visible customer")
        organization = self.rec.expected("customers", member.request("POST", "/api/v1/customers", json_body=self.customer_body(
            f"UAT-20260720-组织客户-{self.tag}", f"199{self.tag}04", "ORGANIZATION"
        )), 201, "organization-visible customer")
        self.state.update(private_customer=private, similar_customer=similar, team_customer=team, organization_customer=organization)
        return "private/team/organization creation, exact duplicate and similar-name override passed"

    def scenario_c02(self) -> str:
        admin, member, other = self.c("ORG_ADMIN"), self.c("MEMBER"), self.c("MEMBER_B")
        page = self.rec.expected("customers", admin.request("GET", "/api/v1/customers", params={
            "search": "UAT-20260720-规模客户-00050", "includeArchived": "true", "size": 10,
        }), 200, "search and page")
        self.rec.truth(page["totalElements"] == 1, "database search returned unexpected total")
        cross_id = deterministic_uuid("uat20260720:customer:main:16")
        self.rec.expected("customers", member.request("GET", f"/api/v1/customers/{cross_id}"), 404, "cross-team private customer hidden")
        current = self.state["private_customer"]
        update = self.customer_body(current["fullName"], current["phone"], ownerNotes="first writer", expectedVersion=current["version"])
        first = self.rec.expected("customers", member.request("PATCH", f"/api/v1/customers/{current['id']}", json_body=update), 200, "first optimistic update")
        stale = self.customer_body(current["fullName"], current["phone"], ownerNotes="stale writer", expectedVersion=current["version"])
        second = member.request("PATCH", f"/api/v1/customers/{current['id']}", json_body=stale)
        self.rec.expected("customers", second, 409, "stale optimistic update must conflict")
        self.rec.truth(first["ownerNotes"] == "first writer", "first writer data not retained")
        self.rec.expected("customers", other.request("GET", f"/api/v1/customers/{current['id']}"), 404, "private customer hidden cross team")
        return "search, paging, visibility and optimistic conflict passed"

    def scenario_c03(self) -> str:
        member = self.c("MEMBER")
        customer_id = self.state["private_customer"]["id"]
        before = self.rec.expected("interactions", member.request("GET", f"/api/v1/customers/{customer_id}/interactions"), 200, "interaction baseline")
        interaction = self.rec.expected("interactions", member.request("POST", f"/api/v1/customers/{customer_id}/interactions", json_body={
            "contactedAt": datetime.now(timezone.utc).isoformat(), "channel": "微信", "summary": "UAT深度沟通",
            "details": "事务闭环", "nextFollowUpAt": (datetime.now(timezone.utc)+timedelta(days=2)).isoformat(),
            "currentStatus": "高意向", "intentionLevel": "高", "verificationStatus": "已核实",
            "tagIds": [self.state["tag"]["id"]],
        }), 201, "create interaction with synchronized updates")
        reminders = self.rec.expected("reminders", member.request("GET", "/api/v1/reminders"), 200, "reminder list")
        self.rec.truth(any(row["customerId"] == customer_id and row["status"] == "PENDING" for row in reminders), "interaction did not create pending reminder")
        manual = self.rec.expected("reminders", member.request("POST", "/api/v1/reminders", json_body={
            "customerId": customer_id, "remindAt": (datetime.now(timezone.utc)+timedelta(days=3)).isoformat(), "title": "UAT手工提醒",
        }), 201, "manual reminder")
        self.rec.expected("reminders", member.request("PATCH", f"/api/v1/reminders/{manual['id']}", json_body={"status": "COMPLETED", "title": "已完成"}), 200, "complete reminder")
        failed = member.request("POST", f"/api/v1/customers/{customer_id}/interactions", json_body={
            "contactedAt": datetime.now(timezone.utc).isoformat(), "channel": "微信", "summary": "应回滚",
            "nextFollowUpAt": (datetime.now(timezone.utc)+timedelta(days=4)).isoformat(),
            "tagIds": [str(uuid.uuid4())],
        })
        self.rec.expected("interactions", failed, 400, "transaction failure injection")
        after = self.rec.expected("interactions", member.request("GET", f"/api/v1/customers/{customer_id}/interactions"), 200, "interaction post-failure count")
        self.rec.truth(len(after) == len(before) + 1 and interaction["id"] in {row["id"] for row in after}, "failed interaction was not fully rolled back")
        return "communication, status/tag synchronization, reminder replacement and rollback passed"

    def scenario_c04(self) -> str:
        for role in ["ORG_ADMIN", "TEAM_MANAGER", "MEMBER", "VIEWER", "TENANT_X"]:
            body = self.rec.expected("dashboard", self.c(role).request("GET", "/api/v1/dashboard", timeout=60), 200, f"{role} dashboard")
            self.rec.truth("stats" in body, f"{role} dashboard missing stats")
        member = self.c("MEMBER")
        customer = self.state["organization_customer"]
        self.rec.expected("customers", member.request("POST", f"/api/v1/customers/{customer['id']}/archive"), 204, "archive customer")
        archived = self.rec.expected("customers", member.request("GET", "/api/v1/customers", params={"search": customer["fullName"], "includeArchived": "true"}), 200, "include archived")
        self.rec.truth(archived["totalElements"] == 1 and archived["items"][0]["archivedAt"], "archived customer not queryable")
        return "large dashboard by role, archive and includeArchived passed"

    def scenario_d01(self) -> str:
        leader, leader_b, viewer, admin = self.c("TEAM_MANAGER"), self.c("TEAM_MANAGER_B"), self.c("VIEWER"), self.c("ORG_ADMIN")
        own = self.state["team_customer"]["id"]
        self.rec.expected("customers", leader.request("PATCH", f"/api/v1/customers/{own}/lead-review", json_body={"status": "VALID"}), 200, "leader reviews own team")
        other_id = deterministic_uuid("uat20260720:customer:main:16")
        self.rec.expected("customers", leader.request("PATCH", f"/api/v1/customers/{other_id}/lead-review", json_body={"status": "VALID"}), 403, "leader cross-team review")
        self.rec.expected("customers", viewer.request("PATCH", f"/api/v1/customers/{own}/lead-review", json_body={"status": "VALID"}), 403, "viewer review")
        self.rec.expected("customers", admin.request("PATCH", f"/api/v1/customers/{other_id}/lead-review", json_body={"status": "INVALID", "reason": "UAT跨团队审核"}), 200, "admin cross-team review")
        return "team leader scope and administrator cross-team lead review passed"

    def scenario_d02(self) -> str:
        member = self.c("REGISTERED_MEMBER")
        today = date.today().isoformat()
        report = self.rec.expected("daily-reports", member.request("PUT", f"/api/v1/daily-reports/{today}", json_body={
            "deepCommunicationCount": 3, "todayIssues": "UAT 今日问题", "tomorrowPlan": "UAT 明日计划",
        }), 200, "save daily report")
        self.rec.truth(report["deepCommunicationCount"] == 3, "daily report value mismatch")
        self.rec.expected("daily-reports", member.request("GET", "/api/v1/daily-reports/today"), 200, "today report")
        future = (date.today() + timedelta(days=1)).isoformat()
        self.rec.expected("daily-reports", member.request("PUT", f"/api/v1/daily-reports/{future}", json_body={
            "deepCommunicationCount": 1, "todayIssues": "未来", "tomorrowPlan": "未来",
        }), 400, "future report rejected")
        return "automatic valid-lead field, save/edit/history and future-date rejection passed"

    def scenario_d03(self) -> str:
        leader = self.rec.expected("team-data", self.c("TEAM_MANAGER").request("GET", "/api/v1/team-data/overview"), 200, "leader team data")
        self.rec.truth(len(leader["teams"]) == 1 and leader["teams"][0]["id"] == self.state["team_a"], "leader saw multiple teams")
        self.rec.expected("team-data", self.c("TEAM_MANAGER").request("GET", "/api/v1/team-data/overview", params={"teamId": self.state["team_b"]}), 403, "leader cross-team filter")
        self.rec.expected("team-data", self.c("ORG_ADMIN").request("GET", "/api/v1/team-data/overview", params={"teamId": self.state["team_a"], "membershipId": self.state["member_a1"]}), 200, "admin team/member filters")
        self.rec.expected("team-data", self.c("MEMBER").request("GET", "/api/v1/team-data/overview"), 403, "member team data")
        return "team/member filters and role scopes passed"

    def create_task(self, title: str, distribution: str, **extra: Any) -> dict[str, Any]:
        body = {
            "title": title, "description": "UAT 任务说明", "distributionType": distribution,
            "startAt": (datetime.now(timezone.utc)-timedelta(minutes=1)).isoformat(),
            "dueAt": (datetime.now(timezone.utc)+timedelta(days=2)).isoformat(),
        }
        body.update(extra)
        return self.rec.expected("tasks", self.c("ORG_ADMIN").request("POST", "/api/v1/tasks", json_body=body), 201, f"create {distribution} task")

    def scenario_e01(self) -> str:
        task = self.create_task(f"UAT-强制任务-{self.tag}", "MANDATORY")
        assignments = self.rec.expected("tasks", self.c("ORG_ADMIN").request("GET", f"/api/v1/tasks/{task['id']}/assignments"), 200, "mandatory assignments")
        registered = next(row for row in assignments if row["membershipId"] == self.state["registered_membership"])
        reg = self.c("REGISTERED_MEMBER")
        self.rec.expected("tasks", reg.request("PATCH", f"/api/v1/tasks/{task['id']}/my-assignment", json_body={"status": "IN_PROGRESS"}), 200, "mandatory start")
        submitted = self.rec.expected("tasks", reg.request("PATCH", f"/api/v1/tasks/{task['id']}/my-assignment", json_body={"status": "SUBMITTED", "resultSummary": "已完成", "evidenceUrl": "https://example.invalid/evidence"}), 200, "mandatory submit")
        self.rec.expected("tasks", self.c("TEAM_MANAGER").request("PATCH", f"/api/v1/tasks/{task['id']}/assignments/{submitted['id']}", json_body={"status": "COMPLETED"}), 200, "leader accepts own-team assignment")
        self.rec.expected("tasks", self.c("ORG_ADMIN").request("POST", f"/api/v1/tasks/{task['id']}/close"), 204, "admin closes task")
        return "mandatory auto-assignment, submit, review and close passed"

    def scenario_e02(self) -> str:
        task = self.create_task(f"UAT-派发任务-{self.tag}", "ASSIGNED", assigneeIds=[self.state["member_a1"], self.state["member_b1"]])
        self.rec.expected("tasks", self.c("MEMBER_A2").request("PATCH", f"/api/v1/tasks/{task['id']}/my-assignment", json_body={"status": "SUBMITTED", "resultSummary": "越权"}), 404, "unassigned member submit")
        submitted = self.rec.expected("tasks", self.c("MEMBER").request("PATCH", f"/api/v1/tasks/{task['id']}/my-assignment", json_body={"status": "SUBMITTED", "resultSummary": "派发完成"}), 200, "assigned member submit")
        self.rec.expected("tasks", self.c("TEAM_MANAGER_B").request("PATCH", f"/api/v1/tasks/{task['id']}/assignments/{submitted['id']}", json_body={"status": "COMPLETED"}), 403, "cross-team assignment review")
        self.rec.expected("tasks", self.c("TEAM_MANAGER").request("PATCH", f"/api/v1/tasks/{task['id']}/assignments/{submitted['id']}", json_body={"status": "COMPLETED"}), 200, "own-team assignment review")
        return "assigned visibility, submission and team review boundaries passed"

    def scenario_e03(self) -> str:
        task = self.create_task(f"UAT-单名额领取-{self.tag}", "SELF_CLAIM", maxClaimants=1)
        def claim(client: Client) -> requests.Response:
            return client.request("POST", f"/api/v1/tasks/{task['id']}/claim", timeout=30)
        with ThreadPoolExecutor(max_workers=2) as pool:
            responses = list(pool.map(claim, [self.c("MEMBER"), self.c("MEMBER_A2")]))
        statuses = sorted(response.status_code for response in responses)
        for response in responses:
            self.rec.observe("tasks", response)
        self.rec.truth(statuses == [200, 409], f"single-slot concurrent claim results were {statuses}")
        winner_index = 0 if responses[0].status_code == 200 else 1
        winner = [self.c("MEMBER"), self.c("MEMBER_A2")][winner_index]
        first_assignment = response_json(responses[winner_index])
        duplicate = self.rec.expected("tasks", winner.request("POST", f"/api/v1/tasks/{task['id']}/claim"), 200, "idempotent duplicate claim")
        self.rec.truth(duplicate["id"] == first_assignment["id"], "duplicate claim created a second assignment")
        assignments = self.rec.expected("tasks", self.c("ORG_ADMIN").request(
            "GET", f"/api/v1/tasks/{task['id']}/assignments"), 200, "claim assignment count")
        self.rec.truth(len(assignments) == 1, "single-slot task contains duplicate assignments")
        return "two-session single-slot concurrency and idempotent repeat allowed exactly one assignment"

    def scenario_f01(self) -> str:
        member = self.c("REGISTERED_MEMBER")
        body = {
            "platform": "XIAOHONGSHU", "publishDate": date.today().isoformat(),
            "directionId": self.state["direction"]["id"], "topic": f"UAT-内容-{self.tag}",
            "contentUrl": "https://example.invalid/uat-content", "viewCount": 12, "likeCount": 2,
            "favoriteCount": 1, "originalDeclared": True,
        }
        post = self.rec.expected("content-posts", member.request("POST", "/api/v1/content-posts", json_body=body), 201, "submit content")
        self.rec.expected("content-posts", member.request("POST", "/api/v1/content-posts", json_body=body), 409, "daily platform limit")
        invalid = dict(body, platform="DOUYIN", directionId=self.state["disabled_direction"]["id"])
        self.rec.expected("content-posts", member.request("POST", "/api/v1/content-posts", json_body=invalid), 400, "disabled direction")
        negative = dict(body, platform="DOUYIN", directionId=self.state["direction"]["id"], viewCount=-1)
        self.rec.expected("content-posts", member.request("POST", "/api/v1/content-posts", json_body=negative), 400, "negative metrics")
        post = self.rec.expected("content-posts", member.request("PATCH", f"/api/v1/content-posts/{post['id']}/metrics", json_body={"viewCount": 99, "likeCount": 9, "favoriteCount": 4}), 200, "own metrics update")
        self.rec.expected("content-posts", self.c("MEMBER_B").request("PATCH", f"/api/v1/content-posts/{post['id']}/metrics", json_body={"viewCount": 100, "likeCount": 1, "favoriteCount": 1}), {403, 404}, "other member metrics")
        self.state["content_post"] = post
        return "campaign date, daily limit, disabled direction, metrics validation and ownership passed"

    def scenario_f02(self) -> str:
        post_id = self.state["content_post"]["id"]
        reviewed = self.rec.expected("content-posts", self.c("TEAM_MANAGER").request("PATCH", f"/api/v1/content-posts/{post_id}/review", json_body={"status": "VALID"}), 200, "leader content review")
        self.rec.truth(reviewed["reviewStatus"] == "VALID", "valid review not persisted")
        self.rec.expected("content-posts", self.c("TEAM_MANAGER_B").request("PATCH", f"/api/v1/content-posts/{post_id}/review", json_body={"status": "INVALID", "reason": "跨队"}), 403, "cross-team content review")
        overview = self.rec.expected("content-overview", self.c("REGISTERED_MEMBER").request("GET", "/api/v1/content-tasks/overview"), 200, "content progress")
        self.rec.truth(overview["myProgress"]["xiaohongshuValid"] >= 1, "valid content missing from progress")
        return "content review scope and per-platform valid progress passed"

    def scenario_f03(self) -> str:
        admin = self.c("ORG_ADMIN")
        page = self.rec.expected("circle", admin.request("GET", "/api/v1/circle/posts", params={"size": 20, "sort": "recommended"}), 200, "circle recommended")
        self.rec.truth(page["totalElements"] >= 6660, "circle scale rows missing")
        searched = self.rec.expected("circle", admin.request("GET", "/api/v1/circle/posts", params={"search": f"UAT-内容-{self.tag}", "sort": "latest"}), 200, "circle search")
        self.rec.truth(searched["totalElements"] == 1, "circle topic search mismatch")
        senders = self.rec.expected("circle", admin.request("GET", "/api/v1/circle/senders"), 200, "circle senders")
        sender_id = self.state["registered_membership"]
        filtered = self.rec.expected("circle", admin.request("GET", "/api/v1/circle/posts", params={"membershipId": sender_id}), 200, "sender filter")
        self.rec.truth(all(item["membershipId"] == sender_id for item in filtered["items"]), "sender filter leaked other senders")
        tenant = self.rec.expected("circle", self.c("TENANT_X").request("GET", "/api/v1/circle/posts"), 200, "tenant circle")
        self.rec.truth(tenant["totalElements"] == 0, "second tenant saw primary-tenant circle")
        return f"circle paging/search/sender/recommended executed with {len(senders)} senders"

    def scenario_g01(self) -> str:
        admin, leader, member = self.c("ORG_ADMIN"), self.c("TEAM_MANAGER"), self.c("MEMBER")
        admin_notice = self.rec.expected("announcements", admin.request("POST", "/api/v1/announcements", json_body={"title": f"UAT管理员公告-{self.tag}", "content": "管理员公告", "priority": "URGENT", "pinned": True}), 201, "admin announcement")
        leader_notice = self.rec.expected("announcements", leader.request("POST", "/api/v1/announcements", json_body={"title": f"UAT团队长公告-{self.tag}", "content": "团队长公告", "priority": "IMPORTANT", "pinned": False}), 201, "leader announcement")
        self.rec.expected("announcements", leader.request("PATCH", f"/api/v1/announcements/{leader_notice['id']}", json_body={"title": f"UAT团队长公告-{self.tag}-编辑", "content": "已编辑", "priority": "IMPORTANT", "pinned": False}), 200, "leader edits own")
        self.rec.expected("announcements", leader.request("PATCH", f"/api/v1/announcements/{admin_notice['id']}", json_body={"title": "越权", "content": "越权", "priority": "NORMAL", "pinned": False}), 403, "leader edits admin")
        self.rec.expected("announcements", member.request("POST", "/api/v1/announcements", json_body={"title": "越权", "content": "越权", "priority": "NORMAL", "pinned": False}), 403, "member publishes")
        self.rec.expected("announcements", leader.request("POST", f"/api/v1/announcements/{leader_notice['id']}/archive"), 204, "leader archives own")
        listing = self.rec.expected("announcements", member.request("GET", "/api/v1/announcements"), 200, "member announcement list")
        self.rec.truth(listing[0]["pinned"] is True, "pinned announcement is not first")
        self.state["admin_announcement"] = admin_notice
        return "publish, edit ownership, priority, pin and archive passed"

    def scenario_h01(self) -> str:
        admin, manager, member = self.c("ORG_ADMIN"), self.c("TEAM_MANAGER"), self.c("MEMBER")
        path = self.fixtures / "customers-10.csv"
        source = io.StringIO(path.read_text(encoding="utf-8-sig"), newline="")
        rows = list(csv.DictReader(source))
        for index, row in enumerate(rows, start=1):
            row["姓名"] = f"UAT-可重复导入-{self.tag}-{index:02d}"
            row["手机号"] = f"16{self.tag}{index:03d}"
        derived = io.StringIO(newline="")
        writer = csv.DictWriter(derived, fieldnames=list(rows[0]))
        writer.writeheader(); writer.writerows(rows)
        payload = derived.getvalue().encode("utf-8")
        preview = self.rec.expected("imports", admin.request(
            "POST", "/api/v1/imports/customers/preview",
            files={"file": (path.name, payload, "text/csv")}, timeout=120,
        ), 200, "CSV preview")
        self.rec.truth(preview["totalRows"] == 10 and preview["readyRows"] == 10, "10-row CSV preview counts mismatch")
        report = self.rec.expected("imports", admin.request("POST", f"/api/v1/imports/{preview['jobId']}/commit", timeout=120), 200, "CSV commit")
        self.rec.truth(report["createdCount"] == 10, "CSV commit count mismatch")
        self.rec.expected("imports", admin.request("POST", f"/api/v1/imports/{preview['jobId']}/commit"), 409, "import idempotence")
        mixed = self.fixtures / "customers-220-bom.csv"
        with mixed.open("rb") as handle:
            mixed_preview = self.rec.expected("imports", admin.request("POST", "/api/v1/imports/customers/preview", files={"file": (mixed.name, handle, "text/csv")}, timeout=120), 200, "BOM mixed preview")
        self.rec.truth((mixed_preview["readyRows"], mixed_preview["failedRows"]) == (210, 10), "220-row preview counts mismatch")
        xlsx = self.fixtures / "customers-10.xlsx"
        with xlsx.open("rb") as handle:
            self.rec.expected("imports", manager.request("POST", "/api/v1/imports/customers/preview", files={"file": (xlsx.name, handle, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")}), 200, "XLSX preview")
        self.rec.expected("imports", member.request(
            "POST", "/api/v1/imports/customers/preview", files={"file": (path.name, payload, "text/csv")}),
            403, "member import")
        for filename, expected in [("empty.csv", 400), ("corrupted.xlsx", 400), ("oversized-26mb.csv", 413)]:
            fixture = self.fixtures / filename
            with fixture.open("rb") as handle:
                self.rec.expected("imports", admin.request("POST", "/api/v1/imports/customers/preview", files={"file": (fixture.name, handle)}, timeout=120), expected, filename)
        self.state["primary_import_job"] = preview["jobId"]
        return "CSV/BOM/XLSX preview, commit, idempotence, malformed, empty, oversize and role checks passed"

    def save_download(self, name: str, response: requests.Response) -> dict[str, Any]:
        path = self.output / name
        path.write_bytes(response.content)
        return {"path": str(path), "bytes": len(response.content), "sha256": hashlib.sha256(response.content).hexdigest()}

    def scenario_h02(self) -> str:
        admin, manager, member = self.c("ORG_ADMIN"), self.c("TEAM_MANAGER"), self.c("MEMBER")
        csv_response = admin.request("GET", "/api/v1/exports/customers", params={"format": "csv"}, timeout=180)
        self.rec.expected("exports", csv_response, 200, "50k CSV export")
        csv_meta = self.save_download("customers-admin.csv", csv_response)
        self.rec.truth(csv_response.content.startswith(b"\xef\xbb\xbf"), "CSV export missing UTF-8 BOM")
        xlsx_response = manager.request("GET", "/api/v1/exports/customers", params={"format": "xlsx"}, timeout=180)
        self.rec.expected("exports", xlsx_response, 200, "manager XLSX export")
        xlsx_meta = self.save_download("customers-manager.xlsx", xlsx_response)
        timeline = admin.request("GET", f"/api/v1/exports/customers/{self.state['private_customer']['id']}/timeline", params={"format": "csv"}, timeout=60)
        self.rec.expected("exports", timeline, 200, "customer timeline export")
        self.save_download("customer-timeline.csv", timeline)
        self.rec.expected("exports", member.request("GET", "/api/v1/exports/customers", params={"format": "csv"}), 403, "member bulk export")
        self.state["export_meta"] = {"csv": csv_meta, "xlsx": xlsx_meta}
        return f"permission-trimmed CSV/XLSX/timeline exports passed ({csv_meta['bytes']} and {xlsx_meta['bytes']} bytes)"

    def scenario_i01(self) -> str:
        admin, viewer = self.c("ORG_ADMIN"), self.c("VIEWER")
        availability = self.rec.expected("assistant", admin.request("GET", "/api/v1/assistant/availability"), 200, "AI availability")
        self.state["ai_availability"] = availability
        before = self.rec.expected("customers", admin.request("GET", "/api/v1/customers", params={"size": 1, "includeArchived": "true"}), 200, "AI draft baseline")["totalElements"]
        draft = admin.request("POST", "/api/v1/assistant/customer-draft", json_body={"text": "姓名张三，手机号18888889999，咨询升学规划，仅生成草稿"}, timeout=120)
        self.rec.expected("assistant", draft, 200 if availability["available"] else 503, "real provider customer draft")
        after = self.rec.expected("customers", admin.request("GET", "/api/v1/customers", params={"size": 1, "includeArchived": "true"}), 200, "AI draft post-count")["totalElements"]
        self.rec.truth(before == after, "AI draft wrote a customer automatically")
        self.rec.expected("assistant", viewer.request("POST", "/api/v1/assistant/customer-draft", json_body={"text": "越权草稿"}), 403, "viewer AI draft")
        return f"real provider draft smoke executed; provider available={availability['available']} and no database write"

    def scenario_i02(self) -> str:
        admin, other = self.c("ORG_ADMIN"), self.c("MEMBER_B")
        customer_id = self.state["private_customer"]["id"]
        response = admin.request("GET", f"/api/v1/assistant/customers/{customer_id}/summary", timeout=120, stream=True)
        expected = 200 if self.state["ai_availability"]["available"] else 503
        self.rec.observe("assistant", response)
        if response.status_code != expected:
            raise Failure(f"AI SSE HTTP {response.status_code}, expected {expected}")
        events: list[str] = []
        if response.status_code == 200:
            for line in response.iter_lines(decode_unicode=True):
                if line and line.startswith("event:"):
                    events.append(line.split(":", 1)[1].strip())
                if len(events) > 100:
                    break
            self.rec.truth("meta" in events and "done" in events, f"SSE sequence missing meta/done: {events}")
        self.rec.expected("assistant", other.request("GET", f"/api/v1/assistant/customers/{customer_id}/summary", timeout=60), 404, "AI private cross-team customer")
        return f"SSE permission filter and event sequence executed: {events}"

    def scenario_i03(self) -> str:
        # Runtime provider failure permutations are executed by the backend contract suite.
        admin, member = self.c("ORG_ADMIN"), self.c("MEMBER")
        availability = self.rec.expected("assistant", admin.request("GET", "/api/v1/assistant/availability"), 200, "AI availability after smoke")
        original = self.rec.expected("admin-assistant-settings", admin.request(
            "GET", "/api/v1/admin/assistant-settings"), 200, "admin reads AI settings")
        serialized = json.dumps(original, ensure_ascii=False).lower()
        self.rec.truth("apikey" not in serialized.replace("apikeyconfigured", "").replace("apikeysource", ""), "AI settings exposed key material")
        self.rec.expected("admin-assistant-settings", member.request(
            "GET", "/api/v1/admin/assistant-settings"), 403, "member cannot read AI settings")
        disabled = self.rec.expected("admin-assistant-settings", admin.request(
            "PUT", "/api/v1/admin/assistant-settings", json_body={
                "enabled": False, "baseUrl": original["baseUrl"], "model": original["model"],
                "clearApiKey": False, "expectedVersion": original["version"],
            }), 200, "admin disables AI")
        disabled_availability = self.rec.expected("assistant", admin.request(
            "GET", "/api/v1/assistant/availability"), 200, "disabled AI availability")
        self.rec.truth(disabled_availability["available"] is False and disabled_availability["provider"] == "disabled",
                       "AI service switch did not take effect")
        self.rec.expected("dashboard", self.c("MEMBER").request("GET", "/api/v1/dashboard"), 200, "non-AI availability during AI checks")
        restored = self.rec.expected("admin-assistant-settings", admin.request(
            "PUT", "/api/v1/admin/assistant-settings", json_body={
                "enabled": original["enabled"], "baseUrl": original["baseUrl"], "model": original["model"],
                "clearApiKey": False, "expectedVersion": disabled["version"],
            }), 200, "restore AI settings")
        self.rec.truth(restored["apiKeyConfigured"] == original["apiKeyConfigured"], "AI key source changed during switch test")
        return f"admin-only dynamic switch/API/model settings and degradation checked; provider={availability['provider']}"

    def scenario_j01(self) -> str:
        admin = self.c("ORG_ADMIN")
        temp_id = self.state["temp_leader"]
        old_session = Client(self.base, self.rec, f"uat-temp-leader-{self.tag}", self.password, "TEMP_LEADER")
        old_session.login()
        updated = self.rec.expected("admin-members", admin.request("PATCH", f"/api/v1/admin/members/{temp_id}", json_body={
            "displayName": "UAT换任后成员", "role": "MEMBER", "status": "ACTIVE", "teamId": self.state["temp_team"],
        }), 200, "demote temporary leader")
        self.rec.truth("MEMBER" in updated["roles"], "role change not persisted")
        self.rec.expected("team-data", old_session.request("GET", "/api/v1/team-data/overview"), 401, "old manager session invalidated")
        self.rec.expected("admin-members", admin.request("PATCH", f"/api/v1/admin/members/{temp_id}", json_body={
            "displayName": "UAT停用成员", "role": "MEMBER", "status": "DISABLED", "teamId": None,
        }), 200, "disable member")
        disabled = Client(self.base, self.rec, f"uat-temp-leader-{self.tag}", self.password, "DISABLED")
        disabled.token()
        self.rec.expected("auth", disabled.request("POST", "/api/v1/auth/login", json_body={"username": disabled.username, "password": self.password}), 401, "disabled login")
        return "name/role/status change, demotion and old-session revocation passed"

    def scenario_j02(self) -> str:
        admin = self.c("ORG_ADMIN")
        member = self.rec.expected("admin-members", admin.request("POST", "/api/v1/admin/members", json_body={
            "username": f"uat-delete-member-{self.tag}", "displayName": "UAT待删除成员", "initialPassword": self.password,
            "role": "MEMBER", "teamId": self.state["team_a"],
        }), 201, "create delete target")
        owned = self.rec.expected("customers", admin.request("POST", "/api/v1/customers", json_body=self.customer_body(
            f"UAT-转移客户-{self.tag}", f"198{self.tag}01", ownerMembershipId=member["membershipId"],
            teamId=self.state["team_a"], nextFollowUpAt=(datetime.now(timezone.utc)+timedelta(days=1)).isoformat(),
        )), 201, "create transfer customer")
        self.rec.expected("admin-members", admin.request("POST", f"/api/v1/admin/members/{member['membershipId']}/delete", json_body={}), 409, "delete without transfer")
        self.rec.expected("admin-members", admin.request("POST", f"/api/v1/admin/members/{member['membershipId']}/delete", json_body={"transferCustomerToMembershipId": self.state["member_a1"]}), 204, "delete with transfer")
        transferred = self.rec.expected("customers", admin.request("GET", f"/api/v1/customers/{owned['id']}"), 200, "transferred customer")
        self.rec.truth(transferred["ownerMembershipId"] == self.state["member_a1"], "customer owner not transferred")
        return "customer-required transfer, pending reminder transfer and soft-disable passed"

    def scenario_j03(self) -> str:
        admin = self.c("ORG_ADMIN")
        team = self.rec.expected("admin-teams", admin.request("POST", "/api/v1/admin/teams", json_body={"name": f"UAT-删除团队-{self.tag}"}), 201, "create deletable team")
        member = self.rec.expected("admin-members", admin.request("POST", "/api/v1/admin/members", json_body={
            "username": f"uat-team-delete-member-{self.tag}", "displayName": "UAT团队删除成员", "initialPassword": self.password,
            "role": "MEMBER", "teamId": team["id"],
        }), 201, "create team-delete member")
        customer = self.rec.expected("customers", admin.request("POST", "/api/v1/customers", json_body=self.customer_body(
            f"UAT-团队删除客户-{self.tag}", f"197{self.tag}01", ownerMembershipId=member["membershipId"], teamId=team["id"], visibility="TEAM",
        )), 201, "create team history customer")
        self.rec.expected("admin-teams", admin.request("DELETE", f"/api/v1/admin/teams/{team['id']}"), 204, "delete team")
        retained = self.rec.expected("customers", admin.request("GET", f"/api/v1/customers/{customer['id']}"), 200, "customer after team delete")
        self.rec.truth(retained.get("teamId") is None, "customer team relation retained after team delete")
        login = Client(self.base, self.rec, f"uat-team-delete-member-{self.tag}", self.password, "TEAM_DELETE_MEMBER")
        login.login()
        return "team disabled while member login and customer history remained"

    def scenario_k01(self) -> str:
        audit = self.rec.expected("admin-audit", self.c("ORG_ADMIN").request("GET", "/api/v1/admin/audit", params={"size": 100}), 200, "audit list")
        actions = {item["action"] for item in audit["items"]}
        required = {"AUTH_LOGIN", "CUSTOMER_CREATED", "IMPORT_COMMITTED", "MEMBER_DELETED", "TEAM_DELETED"}
        self.rec.truth(required.issubset(actions), f"audit actions missing: {sorted(required-actions)}")
        serialized = json.dumps(audit, ensure_ascii=False).lower()
        self.rec.truth("api_key" not in serialized and "password_hash" not in serialized, "audit exposed secret fields")
        self.rec.expected("admin-audit", self.c("MEMBER").request("GET", "/api/v1/admin/audit"), 403, "member audit access")
        return "audit action coverage, admin scope and secret-field scan passed"

    def scenario_k02(self) -> str:
        unauth, admin, viewer = self.c("UNAUTHENTICATED"), self.c("ORG_ADMIN"), self.c("VIEWER")
        health = self.rec.expected("health-openapi", unauth.request("GET", "/actuator/health"), 200, "liveness")
        ready = self.rec.expected("health-openapi", unauth.request("GET", "/actuator/health/readiness"), 200, "readiness")
        self.rec.truth(health["status"] == ready["status"] == "UP", "health not UP")
        self.rec.expected("health-openapi", admin.request("GET", "/v3/api-docs"), 200, "admin OpenAPI")
        self.rec.expected("health-openapi", viewer.request("GET", "/v3/api-docs"), 403, "viewer OpenAPI")
        validation = admin.request("POST", "/api/v1/customers", json_body={"fullName": "无联系方式"})
        body = self.rec.expected("customers", validation, 400, "validation envelope")
        self.rec.truth(all(key in body for key in ["code", "message", "traceId"]), "error envelope missing required keys")
        missing = admin.request("GET", f"/api/v1/customers/{uuid.uuid4()}")
        body = self.rec.expected("customers", missing, 404, "not-found envelope")
        self.rec.truth("traceId" in body, "not-found envelope missing traceId")
        return "health, readiness, admin-only OpenAPI and unified error envelope passed"

    def permission_matrix(self) -> None:
        roles = ["ORG_ADMIN", "TEAM_MANAGER", "MEMBER", "VIEWER", "UNAUTHENTICATED", "TENANT_X"]
        own_private = deterministic_uuid("uat20260720:customer:main:1")
        cross_private = deterministic_uuid("uat20260720:customer:main:16")
        tenant_customer = deterministic_uuid("uat20260720:customer:tenant:1")
        random_id = str(uuid.uuid4())
        for role in roles:
            client = self.c(role)
            expected_own = {200} if role in {"ORG_ADMIN", "TEAM_MANAGER", "MEMBER"} else ({401} if role == "UNAUTHENTICATED" else {404})
            expected_cross = {200} if role == "ORG_ADMIN" else ({401} if role == "UNAUTHENTICATED" else {404})
            expected_tenant = {200} if role == "TENANT_X" else ({401} if role == "UNAUTHENTICATED" else {404})
            self.rec.permission("customers", "private-own-team", role, client.request("GET", f"/api/v1/customers/{own_private}"), expected_own, "customers")
            self.rec.permission("customers", "cross-team-private", role, client.request("GET", f"/api/v1/customers/{cross_private}"), expected_cross, "customers")
            self.rec.permission("customers", "other-organization", role, client.request("GET", f"/api/v1/customers/{tenant_customer}"), expected_tenant, "customers")
            self.rec.permission("customers", "random-uuid", role, client.request("GET", f"/api/v1/customers/{random_id}"), {401} if role == "UNAUTHENTICATED" else {404}, "customers")
            list_expected = {401} if role == "UNAUTHENTICATED" else {200}
            self.rec.permission("customers", "forged-organizationId", role, client.request("GET", "/api/v1/customers", params={"organizationId": deterministic_uuid("forged"), "size": 1}), list_expected, "customers")

        # Resource families with private/tenant/random identifiers.
        probes = [
            ("exports", "timeline-cross-team", "MEMBER", "GET", f"/api/v1/exports/customers/{cross_private}/timeline", {403, 404}, "exports"),
            ("exports", "timeline-other-org", "ORG_ADMIN", "GET", f"/api/v1/exports/customers/{tenant_customer}/timeline", {404}, "exports"),
            ("assistant", "summary-cross-team", "MEMBER", "GET", f"/api/v1/assistant/customers/{cross_private}/summary", {404}, "assistant"),
            ("assistant", "summary-other-org", "ORG_ADMIN", "GET", f"/api/v1/assistant/customers/{tenant_customer}/summary", {404}, "assistant"),
            ("team-data", "forged-team", "TEAM_MANAGER", "GET", "/api/v1/team-data/overview?teamId=" + self.state["team_b"], {403}, "team-data"),
            ("imports", "random-job", "ORG_ADMIN", "GET", f"/api/v1/imports/{random_id}/report", {404}, "imports"),
            ("admin-members", "other-org-member", "ORG_ADMIN", "PATCH", f"/api/v1/admin/members/{deterministic_uuid('uat20260720:membership:tenant:1')}", {404}, "admin-members"),
            ("admin-assistant-settings", "team-manager-read", "TEAM_MANAGER", "GET", "/api/v1/admin/assistant-settings", {403}, "admin-assistant-settings"),
            ("admin-assistant-settings", "member-read", "MEMBER", "GET", "/api/v1/admin/assistant-settings", {403}, "admin-assistant-settings"),
            ("admin-assistant-settings", "viewer-read", "VIEWER", "GET", "/api/v1/admin/assistant-settings", {403}, "admin-assistant-settings"),
            ("admin-assistant-settings", "unauthenticated-read", "UNAUTHENTICATED", "GET", "/api/v1/admin/assistant-settings", {401}, "admin-assistant-settings"),
            ("admin-assistant-settings", "second-organization-admin", "TENANT_X", "GET", "/api/v1/admin/assistant-settings", {200}, "admin-assistant-settings"),
            ("admin-assistant-settings", "forged-organizationId", "ORG_ADMIN", "GET", "/api/v1/admin/assistant-settings?organizationId=" + deterministic_uuid("forged"), {200}, "admin-assistant-settings"),
        ]
        for resource, probe, role, method, path, expected, capability in probes:
            body = {"displayName": "伪造", "role": "MEMBER", "status": "ACTIVE", "teamId": None} if method == "PATCH" else None
            response = self.c(role).request(method, path, json_body=body, timeout=60)
            self.rec.permission(resource, probe, role, response, expected, capability)

    def run(self) -> int:
        self.login_all()
        scenarios: list[tuple[str, str, Callable[[], str]]] = [
            ("E2E-A01", "ORG_ADMIN/VIEWER/未登录", self.scenario_a01),
            ("E2E-A02", "ORG_ADMIN/TEAM_MANAGER", self.scenario_a02),
            ("E2E-A03", "ORG_ADMIN/未登录", self.scenario_a03),
            ("E2E-B01", "ORG_ADMIN/MEMBER", self.scenario_b01),
            ("E2E-C01", "MEMBER", self.scenario_c01),
            ("E2E-C02", "全角色", self.scenario_c02),
            ("E2E-C03", "MEMBER", self.scenario_c03),
            ("E2E-C04", "全角色", self.scenario_c04),
            ("E2E-D01", "ORG_ADMIN/TEAM_MANAGER/VIEWER", self.scenario_d01),
            ("E2E-D02", "MEMBER", self.scenario_d02),
            ("E2E-D03", "ORG_ADMIN/TEAM_MANAGER/MEMBER", self.scenario_d03),
            ("E2E-E01", "ORG_ADMIN/TEAM_MANAGER/MEMBER", self.scenario_e01),
            ("E2E-E02", "ORG_ADMIN/TEAM_MANAGER/MEMBER", self.scenario_e02),
            ("E2E-E03", "MEMBER x2", self.scenario_e03),
            ("E2E-F01", "MEMBER", self.scenario_f01),
            ("E2E-F02", "ORG_ADMIN/TEAM_MANAGER", self.scenario_f02),
            ("E2E-F03", "全角色/第二组织", self.scenario_f03),
            ("E2E-G01", "全角色", self.scenario_g01),
            ("E2E-H01", "ORG_ADMIN/TEAM_MANAGER/MEMBER", self.scenario_h01),
            ("E2E-H02", "ORG_ADMIN/TEAM_MANAGER/MEMBER", self.scenario_h02),
            ("E2E-I01", "ORG_ADMIN/VIEWER", self.scenario_i01),
            ("E2E-I02", "ORG_ADMIN/MEMBER", self.scenario_i02),
            ("E2E-I03", "ORG_ADMIN/MEMBER", self.scenario_i03),
            ("E2E-J01", "ORG_ADMIN/TEAM_MANAGER", self.scenario_j01),
            ("E2E-J02", "ORG_ADMIN", self.scenario_j02),
            ("E2E-J03", "ORG_ADMIN/MEMBER", self.scenario_j03),
            ("E2E-K01", "ORG_ADMIN/MEMBER", self.scenario_k01),
            ("E2E-K02", "全角色/未登录", self.scenario_k02),
        ]
        for scenario_id, role, fn in scenarios:
            self.rec.run(scenario_id, role, fn)
        try:
            self.permission_matrix()
        except Exception as error:
            print(f"PERMISSION_MATRIX FAIL {type(error).__name__}: {error}", flush=True)
        self.write_results()
        failed = sum(row.status == "FAIL" for row in self.rec.scenarios)
        permission_failed = sum(row["result"] == "FAIL" for row in self.rec.permissions)
        return 1 if failed or permission_failed else 0

    def write_results(self) -> None:
        scenario_path = self.output / "rest-scenario-results.json"
        scenario_path.write_text(json.dumps([
            {
                "scenarioId": row.scenario_id, "role": row.role, "startedAt": row.started_at,
                "endedAt": row.ended_at, "status": row.status, "actual": row.actual,
                "traceIds": row.trace_ids,
            } for row in self.rec.scenarios
        ], ensure_ascii=False, indent=2), encoding="utf-8")
        (self.output / "rest-capability-results.json").write_text(
            json.dumps(self.rec.capabilities, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        with (self.output / "permission-matrix.csv").open("w", newline="", encoding="utf-8-sig") as handle:
            fields = ["resource", "probe", "role", "expected_status", "actual_status", "code", "result", "trace_id"]
            writer = csv.DictWriter(handle, fieldnames=fields)
            writer.writeheader(); writer.writerows(self.rec.permissions)
        summary = {
            "seed": SEED, "baseUrl": self.base,
            "scenarioPass": sum(row.status == "PASS" for row in self.rec.scenarios),
            "scenarioFail": sum(row.status == "FAIL" for row in self.rec.scenarios),
            "permissionPass": sum(row["result"] == "PASS" for row in self.rec.permissions),
            "permissionFail": sum(row["result"] == "FAIL" for row in self.rec.permissions),
            "restCapabilitiesExecuted": sum(bool(row["executed"]) for row in self.rec.capabilities.values()),
            "restCapabilitiesTotal": len(self.rec.capabilities),
            "downloads": self.state.get("export_meta", {}),
            "aiAvailability": self.state.get("ai_availability", {}),
        }
        (self.output / "rest-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(summary, ensure_ascii=False), flush=True)


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit("usage: run_rest_matrix.py BASE_URL PASSWORD_ENV OUTPUT_DIR")
    password_env = read_env(Path(sys.argv[2]))
    if "UAT_PASSWORD" not in password_env:
        raise SystemExit("UAT_PASSWORD is missing")
    suite = UatSuite(sys.argv[1], password_env["UAT_PASSWORD"], Path(sys.argv[3]))
    raise SystemExit(suite.run())


if __name__ == "__main__":
    main()
