#!/usr/bin/env python3
"""Validate the supplied enrichment workbook against an isolated restored snapshot."""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

import requests


def read_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        if raw.strip() and not raw.lstrip().startswith("#") and "=" in raw:
            key, value = raw.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def require(response: requests.Response, expected: int) -> dict:
    if response.status_code != expected:
        try:
            detail = response.json().get("code", response.text[:200])
        except ValueError:
            detail = response.text[:200]
        raise AssertionError(f"{response.request.method} {response.url}: {response.status_code} {detail}")
    return response.json() if response.content else {}


def main() -> None:
    if len(sys.argv) != 5:
        raise SystemExit(
            "usage: customer_enrichment_snapshot_uat.py BASE_URL PASSWORD_ENV WORKBOOK OUTPUT_JSON"
        )
    base = sys.argv[1].rstrip("/")
    credentials = read_env(Path(sys.argv[2]))
    username = credentials.get("UAT_USERNAME", "uat-admin")
    password = credentials.get("UAT_PASSWORD")
    if not password:
        raise SystemExit("UAT_PASSWORD missing")
    workbook = Path(sys.argv[3])
    payload = workbook.read_bytes()
    sha256 = hashlib.sha256(payload).hexdigest()

    session = requests.Session()
    session.trust_env = False
    csrf = require(session.get(f"{base}/api/v1/auth/csrf", timeout=30), 200)["token"]
    require(session.post(
        f"{base}/api/v1/auth/login",
        json={"username": username, "password": password},
        headers={"X-XSRF-TOKEN": csrf}, timeout=30,
    ), 200)
    headers = {"X-XSRF-TOKEN": csrf}

    def preview() -> dict:
        return require(session.post(
            f"{base}/api/v1/admin/customer-enrichment-jobs/preview",
            headers=headers,
            files={"file": (workbook.name, payload,
                             "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")},
            timeout=180,
        ), 200)

    first = preview()
    job = first["job"]
    rows = first["rows"]
    if job["fileSha256"] != sha256:
        raise AssertionError("server workbook SHA-256 differs from the supplied file")
    if job["totalRows"] != 583 or len(rows) != 583:
        raise AssertionError(f"expected 583 parsed rows, got job={job['totalRows']} response={len(rows)}")
    unverified = sum(row.get("mappingStatus") != "已匹配" for row in rows)
    if unverified != 49:
        raise AssertionError(f"expected 49 unverified location mappings, got {unverified}")

    committed = require(session.post(
        f"{base}/api/v1/admin/customer-enrichment-jobs/{job['id']}/commit",
        headers=headers, timeout=180,
    ), 200)
    if committed["job"]["status"] != "COMPLETED":
        raise AssertionError("enrichment commit did not reach COMPLETED")
    repeated = preview()
    if repeated["job"]["id"] != job["id"] or repeated["job"]["status"] != "COMPLETED":
        raise AssertionError("same-file retry did not return the completed idempotent job")
    report = session.get(
        f"{base}/api/v1/admin/customer-enrichment-jobs/{job['id']}/report.csv",
        timeout=60,
    )
    if report.status_code != 200 or not report.content.startswith(b"\xef\xbb\xbf"):
        raise AssertionError("row-level CSV report download failed")

    statuses: dict[str, int] = {}
    for row in committed["rows"]:
        statuses[row["status"]] = statuses.get(row["status"], 0) + 1
    result = {
        "status": "PASS",
        "jobId": job["id"],
        "fileSha256": sha256,
        "totalRows": committed["job"]["totalRows"],
        "readyRows": committed["job"]["readyRows"],
        "updatedRows": committed["job"]["updatedRows"],
        "skippedRows": committed["job"]["skippedRows"],
        "conflictRows": committed["job"]["conflictRows"],
        "unverifiedLocationRows": unverified,
        "rowStatuses": statuses,
        "idempotentRetry": True,
        "reportSha256": hashlib.sha256(report.content).hexdigest(),
    }
    Path(sys.argv[4]).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
