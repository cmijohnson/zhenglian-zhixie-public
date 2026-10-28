#!/usr/bin/env python3
"""Acquire and verify a persisted JDBC-backed Session without printing its value."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

import requests


def env_value(path: Path, key: str) -> str:
    for raw in path.read_text(encoding="utf-8").splitlines():
        if raw.startswith(key + "="):
            return raw.split("=", 1)[1]
    raise RuntimeError(f"{key} is missing")


def optional_env_value(path: Path, key: str, fallback: str) -> str:
    try:
        return env_value(path, key)
    except RuntimeError:
        return fallback


def acquire(base: str, env_file: Path, state_file: Path, username_key: str, password_key: str) -> None:
    session = requests.Session(); session.trust_env = False
    csrf = session.get(base + "/api/v1/auth/csrf", timeout=30)
    csrf.raise_for_status()
    login = session.post(base + "/api/v1/auth/login", json={
        "username": optional_env_value(env_file, username_key, "uat-admin"),
        "password": env_value(env_file, password_key),
    }, headers={"X-XSRF-TOKEN": csrf.json()["token"]}, timeout=30)
    login.raise_for_status()
    cookie = session.cookies.get("YINGGE_SESSION")
    if not cookie:
        raise RuntimeError("YINGGE_SESSION was not issued")
    # UAT advertises a public HTTPS issuer while the verification call uses the
    # loopback HTTP-only gateway. Send the already-issued Secure cookie
    # explicitly for this local infrastructure check.
    me = requests.get(base + "/api/v1/auth/me", headers={
        "Cookie": "YINGGE_SESSION=" + cookie,
    }, timeout=30)
    me.raise_for_status()
    state_file.write_text(json.dumps({"cookie": cookie, "username": me.json()["username"]}), encoding="utf-8")
    os.chmod(state_file, 0o600)
    print(json.dumps({"acquired": True, "username": me.json()["username"]}))


def verify(base: str, state_file: Path, report_file: Path) -> None:
    state = json.loads(state_file.read_text(encoding="utf-8"))
    response = requests.get(base + "/api/v1/auth/me", cookies={"YINGGE_SESSION": state["cookie"]}, timeout=30)
    body = response.json() if response.headers.get("content-type", "").startswith("application/json") else {}
    result = {
        "postRebuildStatus": response.status_code,
        "expectedUsername": state["username"],
        "actualUsername": body.get("username"),
        "sessionPersisted": response.status_code == 200 and body.get("username") == state["username"],
        "sessionValueRecorded": False,
    }
    report_file.parent.mkdir(parents=True, exist_ok=True)
    report_file.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    state_file.unlink(missing_ok=True)
    print(json.dumps(result, ensure_ascii=False))
    if not result["sessionPersisted"]:
        raise SystemExit(1)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("acquire", "verify"))
    parser.add_argument("--base", required=True)
    parser.add_argument("--state", type=Path, required=True)
    parser.add_argument("--env-file", type=Path)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--username-key", default="UAT_USERNAME")
    parser.add_argument("--password-key", default="UAT_PASSWORD")
    args = parser.parse_args()
    if args.mode == "acquire":
        if not args.env_file: parser.error("--env-file is required for acquire")
        acquire(args.base.rstrip("/"), args.env_file, args.state, args.username_key, args.password_key)
    else:
        if not args.report: parser.error("--report is required for verify")
        verify(args.base.rstrip("/"), args.state, args.report)


if __name__ == "__main__":
    main()
