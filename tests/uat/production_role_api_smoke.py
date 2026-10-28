#!/usr/bin/env python3
"""Ephemeral production role smoke test executed on the target host.

The script reads bootstrap credentials from an existing server-side env file,
never prints them, creates members with no business data, verifies role and
permission boundaries, then removes every temporary member and team.
"""

from __future__ import annotations

import argparse
import json
import secrets
import time
import urllib.error
import urllib.request
from http.cookiejar import CookieJar
from pathlib import Path
from typing import Any


def env_value(path: Path, key: str) -> str:
    prefix = f"{key}="
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith(prefix):
            return line[len(prefix):]
    raise RuntimeError(f"missing required environment key: {key}")


class Client:
    def __init__(self, base: str):
        self.base = base.rstrip("/")
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CookieJar()))

    def call(self, method: str, path: str, body: Any = None) -> tuple[int, Any]:
        headers = {"Accept": "application/json"}
        data = None
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if method not in {"GET", "HEAD", "OPTIONS"}:
            status, csrf = self.call("GET", "/api/v1/auth/csrf")
            if status != 200 or not isinstance(csrf, dict) or not csrf.get("token"):
                raise RuntimeError("unable to obtain CSRF token")
            headers["X-XSRF-TOKEN"] = str(csrf["token"])
        request = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=20) as response:
                raw = response.read()
                return response.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            raw = error.read()
            try:
                parsed = json.loads(raw) if raw else None
            except json.JSONDecodeError:
                parsed = None
            return error.code, parsed

    def login(self, username: str, password: str) -> tuple[int, Any]:
        return self.call("POST", "/api/v1/auth/login", {"username": username, "password": password})


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1")
    parser.add_argument("--env", type=Path, required=True)
    args = parser.parse_args()
    admin_username = env_value(args.env, "BOOTSTRAP_ADMIN_USERNAME")
    admin_password = env_value(args.env, "BOOTSTRAP_ADMIN_PASSWORD")
    marker = f"mobile-{int(time.time())}-{secrets.token_hex(3)}"
    results: list[dict[str, Any]] = []
    created_members: list[str] = []
    created_team = ""

    def check(scenario: str, actual: Any, expected: Any) -> None:
        results.append({"scenario": scenario, "actual": actual, "expected": expected,
                        "result": "PASS" if actual == expected else "FAIL"})

    admin = Client(args.base)
    status, me = admin.login(admin_username, admin_password)
    check("ORG_ADMIN_LOGIN", status, 200)
    check("ORG_ADMIN_ROLE", "ORG_ADMIN" in (me or {}).get("roles", []), True)
    try:
        status, team = admin.call("POST", "/api/v1/admin/teams", {"name": f"移动验收临时团队-{marker}"})
        check("TEMP_TEAM_CREATE", status, 201)
        created_team = str((team or {}).get("id", ""))
        if not created_team:
            raise RuntimeError("temporary team id is missing")

        role_accounts: list[tuple[str, str, str]] = []
        for role in ("MEMBER", "VIEWER", "TEAM_MANAGER"):
            username = f"uat_{role.lower()}_{marker}"
            password = f"Tmp!{secrets.token_urlsafe(18)}9a"
            status, member = admin.call("POST", "/api/v1/admin/members", {
                "username": username,
                "displayName": f"移动验收 {role}",
                "initialPassword": password,
                "role": role,
                "teamId": created_team,
            })
            check(f"{role}_CREATE", status, 201)
            membership_id = str((member or {}).get("membershipId", ""))
            if not membership_id:
                raise RuntimeError(f"{role} membership id is missing")
            created_members.append(membership_id)
            role_accounts.append((role, username, password))

        disabled_username = f"uat_disabled_{marker}"
        disabled_password = f"Tmp!{secrets.token_urlsafe(18)}9a"
        status, disabled = admin.call("POST", "/api/v1/admin/members", {
            "username": disabled_username,
            "displayName": "移动验收 DISABLED",
            "initialPassword": disabled_password,
            "role": "MEMBER",
            "teamId": created_team,
        })
        check("DISABLED_CREATE", status, 201)
        disabled_id = str((disabled or {}).get("membershipId", ""))
        created_members.append(disabled_id)
        status, _ = admin.call("PATCH", f"/api/v1/admin/members/{disabled_id}", {
            "displayName": "移动验收 DISABLED",
            "role": "MEMBER",
            "status": "DISABLED",
            "teamId": created_team,
        })
        check("DISABLED_SET", status, 200)

        for role, username, password in role_accounts:
            client = Client(args.base)
            status, current = client.login(username, password)
            check(f"{role}_LOGIN", status, 200)
            check(f"{role}_IDENTITY", role in (current or {}).get("roles", []), True)
            status, _ = client.call("GET", "/api/v1/customers?page=0&size=1")
            check(f"{role}_CUSTOMERS", status, 200)
            status, _ = client.call("GET", "/api/v1/notifications?unreadOnly=true&size=1")
            check(f"{role}_NOTIFICATIONS", status, 200)
            status, _ = client.call("GET", "/api/v1/admin/members")
            check(f"{role}_ADMIN_DENIED", status, 403)

        disabled_client = Client(args.base)
        status, _ = disabled_client.login(disabled_username, disabled_password)
        check("DISABLED_LOGIN_DENIED", status in {401, 403}, True)
        anonymous = Client(args.base)
        status, _ = anonymous.call("GET", "/api/v1/auth/me")
        check("UNAUTHENTICATED_ME_DENIED", status, 401)
    finally:
        for membership_id in reversed(created_members):
            if not membership_id:
                continue
            status, _ = admin.call("POST", f"/api/v1/admin/members/{membership_id}/delete", {})
            check(f"CLEAN_MEMBER_{len(created_members)}", status, 204)
        if created_team:
            status, _ = admin.call("DELETE", f"/api/v1/admin/teams/{created_team}")
            check("CLEAN_TEAM", status, 204)

    summary = {
        "checks": len(results),
        "pass": sum(item["result"] == "PASS" for item in results),
        "fail": sum(item["result"] == "FAIL" for item in results),
        "results": results,
    }
    print(json.dumps(summary, ensure_ascii=False))
    return 1 if summary["fail"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
