#!/usr/bin/env python3
"""Rotate the isolated UAT admin to a temporary test password without exposing either value."""

from __future__ import annotations

import json
import sys
import urllib.error
import urllib.request
from http.cookiejar import CookieJar
from pathlib import Path


def read_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key] = value
    return values


def request(opener: urllib.request.OpenerDirector, url: str, method: str = "GET",
            body: dict[str, object] | None = None, token: str | None = None) -> tuple[int, bytes]:
    headers = {"Accept": "application/json"}
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["X-XSRF-TOKEN"] = token
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with opener.open(req, timeout=30) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: bootstrap_test_password.py OLD_ENV NEW_ENV")
    old = read_env(Path(sys.argv[1]))
    new = read_env(Path(sys.argv[2]))
    required_old = {"UAT_ADMIN_USERNAME", "UAT_ADMIN_PASSWORD"}
    if not required_old.issubset(old) or "UAT_PASSWORD" not in new:
        raise SystemExit("required UAT credential keys are missing")

    base = "http://127.0.0.1:18080"
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CookieJar()))
    status, payload = request(opener, f"{base}/api/v1/auth/csrf")
    if status != 200:
        raise SystemExit(f"csrf failed with HTTP {status}")
    token = json.loads(payload)["token"]
    status, _ = request(
        opener, f"{base}/api/v1/auth/login", "POST",
        {"username": old["UAT_ADMIN_USERNAME"], "password": old["UAT_ADMIN_PASSWORD"]}, token,
    )
    if status != 200:
        raise SystemExit(f"admin login failed with HTTP {status}")
    status, _ = request(
        opener, f"{base}/api/v1/auth/password", "POST",
        {"currentPassword": old["UAT_ADMIN_PASSWORD"], "newPassword": new["UAT_PASSWORD"]}, token,
    )
    if status != 204:
        raise SystemExit(f"password rotation failed with HTTP {status}")
    print("isolated UAT test password installed")


if __name__ == "__main__":
    main()
