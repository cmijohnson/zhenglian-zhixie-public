#!/usr/bin/env python3
"""Create a disposable pending OAuth request for isolated browser UAT."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):  # type: ignore[no-untyped-def]
        return None


def read_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key] = value
    return values


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env", required=True, type=Path)
    parser.add_argument("--base", default="http://127.0.0.1:19090")
    args = parser.parse_args()
    values = read_env(args.env)
    opener = urllib.request.build_opener(NoRedirect())
    payload = json.dumps({
        "client_name": "浏览器验收 Agent",
        "redirect_uris": ["http://127.0.0.1:43123/callback"],
        "grant_types": ["authorization_code", "refresh_token"],
        "response_types": ["code"],
        "token_endpoint_auth_method": "none",
    }).encode("utf-8")
    register = urllib.request.Request(args.base + "/oauth2/register", data=payload, method="POST", headers={
        "Accept": "application/json", "Content-Type": "application/json",
    })
    with opener.open(register, timeout=30) as response:
        client = json.loads(response.read())
    verifier = "browser-uat-verifier-abcdefghijklmnopqrstuvwxyz-0123456789"
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode("ascii")).digest()).rstrip(b"=").decode()
    query = urllib.parse.urlencode({
        "response_type": "code",
        "client_id": client["client_id"],
        "redirect_uri": "http://127.0.0.1:43123/callback",
        "scope": "yingge.read yingge.write yingge.ai yingge.admin",
        "state": "browser-uat",
        "code_challenge": challenge,
        "code_challenge_method": "S256",
        "resource": values["MCP_UAT_RESOURCE_URI"],
    })
    try:
        opener.open(args.base + "/oauth2/authorize?" + query, timeout=30)
        raise SystemExit("authorization endpoint did not redirect")
    except urllib.error.HTTPError as error:
        if error.code != 302:
            raise
        request_id = urllib.parse.parse_qs(urllib.parse.urlparse(error.headers["Location"]).query)["request"][0]
    print(request_id)


if __name__ == "__main__":
    main()
