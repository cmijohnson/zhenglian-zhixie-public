#!/usr/bin/env python3
"""Time the formal 5,000-row import and full customer exports without logging credentials."""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import os
import time
from datetime import datetime, timezone
from pathlib import Path

import requests


class UatClient:
    def __init__(self, base_url: str, username: str, password: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.password = password
        self.username = username
        self.session = requests.Session()
        self.session.trust_env = False
        response = self.session.get(f"{self.base_url}/api/v1/auth/csrf", timeout=30)
        response.raise_for_status()
        self.csrf = response.json()["token"]
        response = self.session.post(
            f"{self.base_url}/api/v1/auth/login",
            json={"username": username, "password": password},
            headers={"X-XSRF-TOKEN": self.csrf},
            timeout=30,
        )
        response.raise_for_status()

    def request(self, method: str, path: str, **kwargs: object) -> requests.Response:
        headers = dict(kwargs.pop("headers", {}) or {})
        if method.upper() not in {"GET", "HEAD", "OPTIONS"}:
            headers["X-XSRF-TOKEN"] = self.csrf
        return self.session.request(method, f"{self.base_url}{path}", headers=headers, **kwargs)


def timed(call):
    started = time.perf_counter()
    response = call()
    return response, round((time.perf_counter() - started) * 1000, 3)


def unique_fixture(source: Path) -> bytes:
    with source.open("r", encoding="utf-8-sig", newline="") as handle:
        rows = list(csv.DictReader(handle))
    run = int(time.time()) % 100000
    for index, row in enumerate(rows, start=1):
        row["姓名"] = f"UAT-5000性能导入-{run:05d}-{index:04d}"
        row["手机号"] = f"15{run:05d}{index:04d}"
    output = io.StringIO(newline="")
    writer = csv.DictWriter(output, fieldnames=list(rows[0]))
    writer.writeheader()
    writer.writerows(rows)
    return ("\ufeff" + output.getvalue()).encode("utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    password = os.environ.get("UAT_PASSWORD")
    if not password:
        raise SystemExit("UAT_PASSWORD is required")

    client = UatClient(args.base_url, "uat-admin", password)
    payload = unique_fixture(args.fixture)
    preview, preview_ms = timed(lambda: client.request(
        "POST", "/api/v1/imports/customers/preview",
        files={"file": ("customers-5000-unique.csv", payload, "text/csv")}, timeout=300,
    ))
    preview.raise_for_status()
    preview_body = preview.json()
    commit, commit_ms = timed(lambda: client.request(
        "POST", f"/api/v1/imports/{preview_body['jobId']}/commit", timeout=300,
    ))
    commit.raise_for_status()
    commit_body = commit.json()

    exports = {}
    for file_format in ("csv", "xlsx"):
        response, duration_ms = timed(lambda fmt=file_format: client.request(
            "GET", "/api/v1/exports/customers", params={"format": fmt}, timeout=300,
        ))
        response.raise_for_status()
        exports[file_format] = {
            "durationMs": duration_ms,
            "bytes": len(response.content),
            "sha256": hashlib.sha256(response.content).hexdigest(),
        }

    result = {
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "fixtureRows": 5000,
        "fixtureSha256": hashlib.sha256(payload).hexdigest(),
        "previewDurationMs": preview_ms,
        "preview": {
            "totalRows": preview_body.get("totalRows"),
            "readyRows": preview_body.get("readyRows"),
            "failedRows": preview_body.get("failedRows"),
        },
        "commitDurationMs": commit_ms,
        "commit": {
            "createdCount": commit_body.get("createdCount"),
            "updatedCount": commit_body.get("updatedCount"),
            "failedCount": commit_body.get("failedCount"),
        },
        "exports": exports,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "previewMs": preview_ms,
        "commitMs": commit_ms,
        "created": commit_body.get("createdCount"),
        "csvMs": exports["csv"]["durationMs"],
        "xlsxMs": exports["xlsx"]["durationMs"],
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
