#!/usr/bin/env python3
"""Open or close a short-lived browser-test password window for isolated UAT.

The credential is read from an owner-only environment file, never printed, and
is atomically replaced after every successful password change.  ``install``
reads a temporary password from the terminal; ``close`` generates a new random
password so the human-known value no longer works after browser verification.
"""

from __future__ import annotations

import argparse
import getpass
import json
import os
import secrets
import stat
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


def read_env(path: Path) -> tuple[list[str], dict[str, str]]:
    lines = path.read_text(encoding="utf-8").splitlines()
    values: dict[str, str] = {}
    for raw in lines:
        line = raw.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key] = value
    return lines, values


def call(opener: urllib.request.OpenerDirector, url: str, method: str = "GET",
         body: dict[str, str] | None = None,
         headers: dict[str, str] | None = None) -> tuple[int, Any, bytes]:
    request_headers = {"Accept": "application/json", **(headers or {})}
    payload = None
    if body is not None:
        request_headers["Content-Type"] = "application/json"
        payload = json.dumps(body).encode("utf-8")
    request = urllib.request.Request(url, data=payload, method=method, headers=request_headers)
    try:
        with opener.open(request, timeout=30) as response:
            return response.status, response.headers, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.headers, error.read()


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


def replace_password(path: Path, lines: list[str], password: str) -> None:
    replaced = False
    output: list[str] = []
    for line in lines:
        if line.startswith("BOOTSTRAP_ADMIN_PASSWORD="):
            output.append("BOOTSTRAP_ADMIN_PASSWORD=" + password)
            replaced = True
        else:
            output.append(line)
    if not replaced:
        raise SystemExit("BOOTSTRAP_ADMIN_PASSWORD is missing")
    temporary = path.with_name(path.name + ".rotate")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            handle.write("\n".join(output) + "\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temporary, stat.S_IRUSR | stat.S_IWUSR)
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env", required=True, type=Path)
    parser.add_argument("--base", default="http://127.0.0.1:19090")
    parser.add_argument("mode", choices=("install", "close"))
    args = parser.parse_args()
    lines, values = read_env(args.env)
    username = values["BOOTSTRAP_ADMIN_USERNAME"]
    current = values["BOOTSTRAP_ADMIN_PASSWORD"]
    replacement = getpass.getpass("Temporary UAT password: ") if args.mode == "install" else secrets.token_urlsafe(36)
    if len(replacement) < 14:
        raise SystemExit("temporary password must contain at least 14 characters")

    opener = urllib.request.build_opener()
    status, response_headers, body = call(opener, args.base + "/api/v1/auth/csrf")
    if status != 200:
        raise SystemExit(f"csrf failed with HTTP {status}")
    csrf = json.loads(body)["token"]
    cookies = cookies_from(response_headers)
    status, response_headers, _ = call(opener, args.base + "/api/v1/auth/login", "POST", {
        "username": username, "password": current,
    }, {"Cookie": cookies, "X-XSRF-TOKEN": csrf})
    if status != 200:
        raise SystemExit(f"login failed with HTTP {status}")
    cookies = cookies_from(response_headers, cookies)
    status, _, _ = call(opener, args.base + "/api/v1/auth/password", "POST", {
        "currentPassword": current, "newPassword": replacement,
    }, {"Cookie": cookies, "X-XSRF-TOKEN": csrf})
    if status != 204:
        raise SystemExit(f"password change failed with HTTP {status}")
    replace_password(args.env, lines, replacement)
    print("isolated UAT browser password window " + ("opened" if args.mode == "install" else "closed"))


if __name__ == "__main__":
    main()
