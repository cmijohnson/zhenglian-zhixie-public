#!/usr/bin/env python3
"""Isolated UAT smoke for leadership ticker and versioned customer intelligence."""

from __future__ import annotations

import hashlib
import json
import sys
import time
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
    if len(sys.argv) != 4:
        raise SystemExit("usage: customer_intelligence_smoke.py BASE_URL PASSWORD_ENV OUTPUT_JSON")
    base = sys.argv[1].rstrip("/")
    credentials = read_env(Path(sys.argv[2]))
    username = credentials.get("UAT_USERNAME", "uat-admin")
    password = credentials.get("UAT_PASSWORD")
    if not password:
        raise SystemExit("UAT_PASSWORD missing")
    session = requests.Session()
    session.trust_env = False
    traces: list[str] = []

    csrf_response = session.get(f"{base}/api/v1/auth/csrf", timeout=30)
    csrf = require(csrf_response, 200)["token"]
    login = session.post(f"{base}/api/v1/auth/login", json={
        "username": username, "password": password,
    }, headers={"X-XSRF-TOKEN": csrf}, timeout=30)
    require(login, 200)
    headers = {"X-XSRF-TOKEN": csrf}

    def request(method: str, path: str, **kwargs) -> requests.Response:
        response = session.request(method, f"{base}{path}", headers=headers, timeout=60, **kwargs)
        if response.headers.get("X-Trace-Id"):
            traces.append(response.headers["X-Trace-Id"])
        return response

    ai = require(request("GET", "/api/v1/admin/assistant-settings"), 200)
    require(request("PUT", "/api/v1/admin/assistant-settings", json={
        "enabled": True,
        "baseUrl": "http://ai-mock:8080/v1",
        "model": "yingge-uat-contract-model",
        "apiKey": "uat-contract-key",
        "clearApiKey": False,
        "expectedVersion": ai["version"],
    }), 200)
    intelligence_settings = require(request("GET", "/api/v1/admin/customer-intelligence/settings"), 200)
    require(request("PUT", "/api/v1/admin/customer-intelligence/settings", json={
        "enabled": True,
        "autoProfileAnalysis": True,
        "autoTimelineAnalysis": True,
        "embeddingBaseUrl": "http://embedding:8080",
        "embeddingModel": "bge-m3",
        "recentInteractionLimit": 10,
        "semanticChunkLimit": 12,
        "backfillEnabled": True,
        "backfillPaused": True,
        "nightlyRatePerMinute": 10,
        "expectedVersion": intelligence_settings["version"],
    }), 200)

    customers = require(request("GET", "/api/v1/customers", params={"size": 1}), 200)
    if not customers.get("items"):
        raise AssertionError("isolated restored snapshot contains no customer")
    customer_id = customers["items"][0]["id"]
    official_before = require(request("GET", f"/api/v1/customers/{customer_id}"), 200)
    official_before_digest = hashlib.sha256(json.dumps(
        official_before, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode()).hexdigest()
    before_overview = require(request("GET", f"/api/v1/customers/{customer_id}/intelligence"), 200)
    before_version = before_overview["latest"]["versionNumber"] if before_overview.get("latest") else 0
    queued = require(request("POST", f"/api/v1/customers/{customer_id}/intelligence/reanalyze"), 202)
    if not queued.get("processing"):
        raise AssertionError("manual analysis was not queued")

    latest = None
    deadline = time.monotonic() + 300
    while time.monotonic() < deadline:
        overview = require(request("GET", f"/api/v1/customers/{customer_id}/intelligence"), 200)
        if overview.get("latest") and overview["latest"]["versionNumber"] > before_version:
            latest = overview["latest"]
            break
        time.sleep(3)
    if not latest:
        raise AssertionError("customer analysis did not produce a version within 300 seconds")
    if latest["versionNumber"] < 1 or not latest.get("narrative") or not latest.get("evidence"):
        raise AssertionError("customer analysis version is incomplete")
    official_after = require(request("GET", f"/api/v1/customers/{customer_id}"), 200)
    official_after_digest = hashlib.sha256(json.dumps(
        official_after, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode()).hexdigest()
    if official_after_digest != official_before_digest:
        raise AssertionError("AI analysis mutated the official customer profile")

    leadership = require(request("GET", "/api/v1/leadership-dashboard", params={"days": 30}), 200)
    ticker_types = {item["type"] for item in leadership.get("tickerItems", [])}
    if not {"FOLLOW_UP_RISK", "SCHOOL_COMPARISON"}.issubset(ticker_types):
        raise AssertionError(f"leadership ticker missing required types: {sorted(ticker_types)}")
    if leadership.get("aClassStale", {}).get("thresholdHours") != 72:
        raise AssertionError("A-class threshold is not the configured default 72 hours")

    result = {
        "status": "PASS",
        "customerId": customer_id,
        "analysisVersion": latest["versionNumber"],
        "previousAnalysisVersion": before_version,
        "analysisTrigger": latest["triggerType"],
        "evidenceCount": len(latest["evidence"]),
        "provider": latest.get("provider"),
        "model": latest.get("model"),
        "officialProfileSha256": official_after_digest,
        "officialProfileUnchanged": True,
        "featuredGrade": leadership.get("featuredGrade"),
        "tickerTypes": sorted(ticker_types),
        "traceIds": list(dict.fromkeys(traces)),
    }
    Path(sys.argv[3]).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
