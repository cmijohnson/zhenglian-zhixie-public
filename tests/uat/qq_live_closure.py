#!/usr/bin/env python3
"""Privacy-preserving live closure suite for an isolated QQ Workbench UAT stack.

The suite intentionally persists only counts, status codes, trace ids and hashes.
QQ identifiers, contact names, group names, message bodies, credentials and media
payloads are held in memory only and are never written to evidence files.
"""

from __future__ import annotations

import csv
import base64
import hashlib
import json
import os
import socket
import sys
import time
import uuid
from dataclasses import dataclass, asdict
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import urlparse

import requests


@dataclass
class Result:
    scenario_id: str
    status: str
    actual: str
    trace_id: str = ""


class Suite:
    def __init__(self, base: str, username: str, password: str, output: Path) -> None:
        self.base = base.rstrip("/")
        self.username = username
        self.password = password
        self.output = output
        self.output.mkdir(parents=True, exist_ok=True)
        self.http = requests.Session()
        self.http.trust_env = False
        self.csrf = ""
        self.results: list[Result] = []
        self.created_task_ids: set[str] = set()
        self.created_schedule_ids: set[str] = set()
        self.created_member_ids: list[str] = []
        self.created_team_id: str | None = None

    def add(self, scenario_id: str, status: str, actual: str, response: requests.Response | None = None) -> None:
        trace = "" if response is None else response.headers.get("X-Trace-Id", "")
        self.results.append(Result(scenario_id, status, actual, trace))
        print(f"{scenario_id} {status}: {actual}", flush=True)

    def call(self, method: str, path: str, *, body: Any = None, params: Any = None,
             stream: bool = False, timeout: int = 90) -> requests.Response:
        headers: dict[str, str] = {}
        if method.upper() not in {"GET", "HEAD", "OPTIONS"}:
            headers["X-XSRF-TOKEN"] = self.csrf
        return self.http.request(method, self.base + path, json=body, params=params,
                                 headers=headers, stream=stream, timeout=timeout)

    @staticmethod
    def body(response: requests.Response) -> Any:
        try:
            return response.json()
        except ValueError:
            return None

    @staticmethod
    def data(response: requests.Response) -> Any:
        body = Suite.body(response)
        if isinstance(body, dict) and "success" in body:
            if body.get("success") is not True:
                raise AssertionError(f"upstream business response failed with HTTP {response.status_code}")
            return body.get("data")
        return body

    @staticmethod
    def digest(value: Any) -> str:
        return hashlib.sha256(str(value).encode("utf-8")).hexdigest()[:12]

    @staticmethod
    def error_code(payload: bytes) -> str:
        try:
            body = json.loads(payload[:8192])
            return str(body.get("code") or body.get("error", {}).get("type") or "") if isinstance(body, dict) else ""
        except Exception:
            return ""

    @staticmethod
    def values(node: Any) -> Iterable[Any]:
        if isinstance(node, dict):
            for value in node.values():
                yield value
                yield from Suite.values(value)
        elif isinstance(node, list):
            for value in node:
                yield value
                yield from Suite.values(value)

    @staticmethod
    def list_of(data: Any, key: str) -> list[dict[str, Any]]:
        if isinstance(data, dict) and isinstance(data.get(key), list):
            return [item for item in data[key] if isinstance(item, dict)]
        return []

    @staticmethod
    def message_categories(node: Any) -> set[str]:
        markers = {"textelement": "text", "picelement": "image", "pttelement": "audio",
                   "videoelement": "video", "fileelement": "file", "marketfaceelement": "emoji",
                   "faceelement": "emoji", "replyelement": "reply", "arkelement": "card",
                   "multiforwardmsgelement": "merged-forward", "graytipelement": "system"}
        found: set[str] = set()
        if isinstance(node, dict):
            for key, value in node.items():
                normalized = key.lower()
                if normalized in markers and value not in (None, {}, []):
                    found.add(markers[normalized])
                found.update(Suite.message_categories(value))
        elif isinstance(node, list):
            for value in node:
                found.update(Suite.message_categories(value))
        return found

    def login(self) -> None:
        token = self.http.get(self.base + "/api/v1/auth/csrf", timeout=30)
        token.raise_for_status()
        self.csrf = str(token.json()["token"])
        response = self.call("POST", "/api/v1/auth/login",
                             body={"username": self.username, "password": self.password})
        if response.status_code != 200:
            raise RuntimeError(f"UAT login failed with HTTP {response.status_code}")
        self.add("QQL-01", "PASS", "isolated UAT login and CSRF succeeded", response)

    def security(self) -> None:
        raw = requests.Session()
        raw.trust_env = False
        unauth = raw.get(self.base + "/api/v1/qq-workbench/bootstrap", timeout=30)
        traversal = self.call("GET", "/api/v1/qq-workbench/qce/api/friends/%252e%252e/security.json")
        arbitrary = self.call("GET", "/api/v1/qq-workbench/qce/api/admin/secrets")
        ok = unauth.status_code == 401 and traversal.status_code in {400, 403} and arbitrary.status_code == 403
        self.add("QQL-02", "PASS" if ok else "FAIL",
                 f"unauth={unauth.status_code}, traversal={traversal.status_code}, arbitrary={arbitrary.status_code}", arbitrary)

    def connection(self) -> None:
        mine = self.call("GET", "/api/v1/qq-connections/me")
        bootstrap = self.call("GET", "/api/v1/qq-workbench/bootstrap")
        static = self.call("GET", "/qq-workbench/")
        connected = mine.status_code == 200 and isinstance(self.body(mine), dict) \
            and self.body(mine).get("status") == "CONNECTED"
        valid_bootstrap = bootstrap.status_code == 200 and self.body(bootstrap).get("status") == "CONNECTED"
        self.add("QQL-03", "PASS" if connected and valid_bootstrap and static.status_code == 200 else "FAIL",
                 f"connection={mine.status_code}, bootstrap={bootstrap.status_code}, workbench={static.status_code}", bootstrap)

    def websocket(self) -> None:
        parsed = urlparse(self.base)
        host = parsed.hostname or "127.0.0.1"
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
        authority = parsed.netloc
        cookies = "; ".join(f"{cookie.name}={cookie.value}" for cookie in self.http.cookies)

        def handshake(origin: str) -> int:
            key = base64.b64encode(os.urandom(16)).decode("ascii")
            request = (f"GET /api/v1/qq-workbench/ws HTTP/1.1\r\nHost: {authority}\r\n"
                       f"Origin: {origin}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                       f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\nCookie: {cookies}\r\n\r\n")
            with socket.create_connection((host, port), timeout=15) as client:
                client.sendall(request.encode("ascii"))
                first = client.recv(1024).split(b"\r\n", 1)[0].decode("ascii", "replace")
            try:
                return int(first.split()[1])
            except Exception:
                return 0

        valid = handshake(f"{parsed.scheme}://{authority}")
        invalid = handshake("http://invalid.example")
        self.add("QQL-18", "PASS" if valid == 101 and invalid != 101 else "FAIL",
                 f"authorized-handshake={valid}; invalid-origin={invalid}")

    def inventory(self) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
        system = self.call("GET", "/api/v1/qq-workbench/qce/api/system/info")
        config = self.call("GET", "/api/v1/qq-workbench/qce/api/config")
        system_data, config_data = self.data(system), self.data(config)
        serialized_config = json.dumps(config_data, ensure_ascii=False).lower()
        leaked = any(marker in serialized_config for marker in ("bridge_token", "access_token", "docker.sock"))
        self.add("QQL-04", "PASS" if system.status_code == 200 and config.status_code == 200 and not leaked else "FAIL",
                 "system status and recursively sanitized configuration verified", config)

        friends: list[dict[str, Any]] = []
        groups: list[dict[str, Any]] = []
        page = 1
        while page <= 100:
            response = self.call("GET", "/api/v1/qq-workbench/qce/api/friends", params={"page": page, "limit": 200})
            data = self.data(response)
            batch = self.list_of(data, "friends")
            friends.extend(batch)
            if not isinstance(data, dict) or not data.get("hasNext"):
                break
            page += 1
        friend_pages = page
        page = 1
        while page <= 100:
            response = self.call("GET", "/api/v1/qq-workbench/qce/api/groups", params={"page": page, "limit": 200})
            data = self.data(response)
            batch = self.list_of(data, "groups")
            groups.extend(batch)
            if not isinstance(data, dict) or not data.get("hasNext"):
                break
            page += 1
        group_pages = page
        recent_response = self.call("GET", "/api/v1/qq-workbench/qce/api/recent-contacts")
        recent = self.list_of(self.data(recent_response), "contacts")
        special = [item for item in recent if item.get("classification") == "special"]

        searchable = False
        if friends:
            candidate = friends[len(friends) // 2]
            exact = str(candidate.get("uin") or "").lower()
            prefix = str(candidate.get("remark") or candidate.get("nick") or "").lower()[:2]
            haystacks = [" ".join(str(row.get(key) or "") for key in ("uin", "uid", "nick", "remark")).lower()
                         for row in friends]
            searchable = bool(exact and any(exact == str(row.get("uin") or "").lower() for row in friends)) \
                and bool(prefix and any(prefix in hay for hay in haystacks))
        self.add("QQL-05", "PASS" if friends and groups and searchable else "FAIL",
                 f"friends={len(friends)} pages={friend_pages}; groups={len(groups)} pages={group_pages}; search=verified")
        self.add("QQL-06", "PASS" if recent else "BLOCKED",
                 f"recent={len(recent)}; special-session-samples={len(special)}")
        return friends, groups, recent

    def fetch_messages(self, peer: dict[str, Any], page: int = 1, limit: int = 50,
                       start: int = 0, end: int | None = None) -> tuple[requests.Response, dict[str, Any]]:
        response = self.call("POST", "/api/v1/qq-workbench/qce/api/messages/fetch", body={
            "peer": peer, "page": page, "limit": limit,
            "filter": {"startTime": start, "endTime": end or int(time.time() * 1000)},
        }, timeout=120)
        data = self.data(response)
        if not isinstance(data, dict):
            raise AssertionError("message response is not structured data")
        return response, data

    @staticmethod
    def friend_peer(friend: dict[str, Any]) -> dict[str, Any]:
        return {"chatType": int(friend.get("chatType") or 1), "peerUid": str(friend.get("uid") or ""),
                "peerUin": str(friend.get("uin") or ""), "guildId": ""}

    @staticmethod
    def group_peer(group: dict[str, Any]) -> dict[str, Any]:
        code = str(group.get("groupCode") or "")
        return {"chatType": 2, "peerUid": code, "peerUin": code, "guildId": ""}

    def preview_and_resources(self, friends: list[dict[str, Any]], groups: list[dict[str, Any]]) -> tuple[dict[str, Any] | None, dict[str, Any] | None]:
        private_sample: dict[str, Any] | None = None
        group_sample: dict[str, Any] | None = None
        message_sets: list[dict[str, Any]] = []
        for friend in friends:
            try:
                response, data = self.fetch_messages(self.friend_peer(friend))
            except Exception:
                continue
            if self.list_of(data, "messages"):
                private_sample = friend
                message_sets.append(data)
                second = None
                if data.get("hasNext"):
                    _, second = self.fetch_messages(self.friend_peer(friend), page=2)
                    message_sets.append(second)
                self.add("QQL-07", "PASS", f"private-preview={len(self.list_of(data, 'messages'))}; deep-page={'yes' if second else 'not-required'}", response)
                break
        for group in groups:
            try:
                response, data = self.fetch_messages(self.group_peer(group))
            except Exception:
                continue
            if self.list_of(data, "messages"):
                group_sample = group
                message_sets.append(data)
                second = None
                if data.get("hasNext"):
                    _, second = self.fetch_messages(self.group_peer(group), page=2)
                    message_sets.append(second)
                self.add("QQL-08", "PASS", f"group-preview={len(self.list_of(data, 'messages'))}; deep-page={'yes' if second else 'not-required'}", response)
                break
        if private_sample is None:
            self.add("QQL-07", "BLOCKED", "no private conversation with messages was available")
        if group_sample is None:
            self.add("QQL-08", "BLOCKED", "no group conversation with messages was available")

        media_tickets: list[str] = []
        type_names: set[str] = set()
        categories: set[str] = set()
        for data in message_sets:
            categories.update(self.message_categories(data))
            for value in self.values(data):
                if isinstance(value, str) and value.startswith("/api/v1/qq-workbench/media/"):
                    media_tickets.append(value)
            for message in self.list_of(data, "messages"):
                for key in ("type", "msgType", "elementType", "subType"):
                    if message.get(key) is not None:
                        type_names.add(str(message[key])[:40])
        if media_tickets:
            attempts: list[tuple[int, str]] = []
            successful: tuple[requests.Response, int, str, bool] | None = None
            for ticket in list(dict.fromkeys(media_tickets))[:20]:
                response = self.call("GET", ticket, stream=True, timeout=120)
                digest, size, sample = hashlib.sha256(), 0, bytearray()
                for chunk in response.iter_content(65536):
                    digest.update(chunk)
                    size += len(chunk)
                    if len(sample) < 8192:
                        sample.extend(chunk[:8192 - len(sample)])
                code = self.error_code(bytes(sample))
                attempts.append((response.status_code, code))
                safe_headers = response.headers.get("Cache-Control", "").lower() == "no-store" \
                    and response.headers.get("X-Content-Type-Options", "").lower() == "nosniff"
                if response.status_code == 200 and size > 0 and safe_headers:
                    successful = response, size, digest.hexdigest()[:16], safe_headers
                    break
            if successful:
                response, size, content_hash, safe_headers = successful
                self.add("QQL-09", "PASS",
                         f"media-ticket attempts={len(attempts)}; bytes={size}; sha256={content_hash}; safe-headers={safe_headers}", response)
            else:
                codes = sorted({code or str(status) for status, code in attempts})
                self.add("QQL-09", "BLOCKED" if all(status in {502, 503, 504} for status, _ in attempts) else "FAIL",
                         f"media-ticket attempts={len(attempts)}; outcomes={','.join(codes)}")
        else:
            self.add("QQL-09", "BLOCKED", f"no proxied media sample; observed-message-types={len(type_names)}")
        expected = {"text", "image", "emoji", "audio", "video", "file", "reply", "card", "merged-forward", "system"}
        missing = sorted(expected - categories)
        self.add("QQL-19", "PASS" if not missing else "BLOCKED",
                 f"observed={','.join(sorted(categories)) or 'none'}; missing-samples={','.join(missing) or 'none'}")
        return private_sample, group_sample

    def group_resources(self, group: dict[str, Any] | None) -> None:
        if not group:
            self.add("QQL-10", "BLOCKED", "no group sample for member/file/album checks")
            return
        code = str(group.get("groupCode"))
        paths = [f"/api/groups/{code}/members", f"/api/groups/{code}/files/count",
                 f"/api/groups/{code}/files", f"/api/groups/{code}/albums",
                 f"/api/groups/{code}/essence", "/api/sticker-packs"]
        statuses: list[int] = []
        counts: list[int] = []
        for path in paths:
            response = self.call("GET", "/api/v1/qq-workbench/qce" + path, timeout=120)
            statuses.append(response.status_code)
            data = self.body(response)
            counts.append(sum(1 for value in self.values(data) if isinstance(value, dict)))
        ok = all(status in {200, 404} for status in statuses)
        self.add("QQL-10", "PASS" if ok else "FAIL",
                 f"members/files/albums/essence/stickers statuses={statuses}; structured-items={sum(counts)}")

    def existing_exports(self) -> None:
        response = self.call("GET", "/api/v1/qq-workbench/qce/api/tasks")
        tasks = self.list_of(self.data(response), "tasks")
        downloadable = [task for task in tasks if task.get("status") == "completed" and task.get("downloadUrl")]
        if not downloadable:
            self.add("QQL-11", "BLOCKED", f"tasks={len(tasks)}; no completed downloadable artifact")
            return
        download = self.call("GET", str(downloadable[0]["downloadUrl"]), stream=True, timeout=180)
        digest, size, sample = hashlib.sha256(), 0, bytearray()
        for chunk in download.iter_content(65536):
            digest.update(chunk)
            size += len(chunk)
            if len(sample) < 8192:
                sample.extend(chunk[:8192 - len(sample)])
        self.add("QQL-11", "PASS" if download.status_code == 200 and size > 0 else "FAIL",
                 f"completed-task download HTTP={download.status_code}; code={self.error_code(bytes(sample))}; bytes={size}; sha256={digest.hexdigest()[:16]}", download)

    def create_export(self, peer: dict[str, Any] | None) -> None:
        if not peer:
            self.add("QQL-12", "BLOCKED", "no safe private peer for disposable export")
            return
        now_ms = int(time.time() * 1000)
        body = {"peer": peer, "sessionName": "UAT-closeout", "format": "JSON",
                "filter": {"startTime": now_ms - 7 * 86400000, "endTime": now_ms, "includeRecalled": False},
                "options": {"batchSize": 100, "includeResourceLinks": False,
                            "includeSystemMessages": True, "filterPureImageMessages": False,
                            "prettyFormat": True, "skipDownloadResourceTypes": ["image", "video", "audio", "file"]}}
        created = self.call("POST", "/api/v1/qq-workbench/qce/api/messages/export", body=body, timeout=120)
        data = self.data(created)
        task_id = str((data or {}).get("taskId") or "") if isinstance(data, dict) else ""
        if not task_id:
            self.add("QQL-12", "FAIL", f"export creation HTTP={created.status_code}", created)
            return
        self.created_task_ids.add(task_id)
        task: dict[str, Any] = {}
        for _ in range(60):
            detail = self.call("GET", f"/api/v1/qq-workbench/qce/api/tasks/{task_id}")
            payload = self.data(detail)
            task = payload.get("task", payload) if isinstance(payload, dict) else {}
            if task.get("status") in {"completed", "failed", "cancelled"}:
                break
            time.sleep(1)
        status = str(task.get("status") or "unknown")
        downloaded = False
        if status == "completed" and task.get("downloadUrl"):
            artifact = self.call("GET", str(task["downloadUrl"]), stream=True, timeout=180)
            artifact_status = artifact.status_code
            first_chunk = next(artifact.iter_content(65536), b"")
            artifact_code = self.error_code(first_chunk)
            downloaded = artifact_status == 200 and bool(first_chunk)
        else:
            artifact_status = 0
            artifact_code = ""
        self.add("QQL-12", "PASS" if status == "completed" and downloaded else "FAIL",
                 f"disposable export status={status}; download-http={artifact_status}; code={artifact_code}; download={downloaded}", created)

    def schedule_crud(self, peer: dict[str, Any] | None) -> None:
        if not peer:
            self.add("QQL-13", "BLOCKED", "no peer for disabled scheduled-export CRUD")
            return
        payload = {"name": "UAT-disabled-closeout", "peer": peer, "sessionName": "UAT-closeout",
                   "scheduleType": "daily", "executeTime": "23:59", "timeRangeType": "yesterday",
                   "format": "JSON", "options": {"includeResourceLinks": False,
                   "includeSystemMessages": True, "filterPureImageMessages": False,
                   "prettyFormat": True, "skipDownloadResourceTypes": ["image", "video", "audio", "file"]},
                   "enabled": False}
        created = self.call("POST", "/api/v1/qq-workbench/qce/api/scheduled-exports", body=payload)
        data = self.data(created)
        schedule_id = str((data or {}).get("id") or (data or {}).get("scheduledExportId") or "") if isinstance(data, dict) else ""
        if schedule_id:
            self.created_schedule_ids.add(schedule_id)
        listed = self.call("GET", "/api/v1/qq-workbench/qce/api/scheduled-exports")
        self.add("QQL-13", "PASS" if created.status_code in {200, 201} and listed.status_code == 200 and schedule_id else "FAIL",
                 f"disabled schedule create={created.status_code}; list={listed.status_code}; id-present={bool(schedule_id)}", created)

    def crm_import(self, friends: list[dict[str, Any]]) -> None:
        marker = os.getenv("QQ_UAT_CUSTOMER_NAME", "QQUAT自动化20260814")
        customers = self.call("GET", "/api/v1/customers", params={"search": marker, "size": 10})
        customer_items = (self.body(customers) or {}).get("items", []) if isinstance(self.body(customers), dict) else []
        exact = [row for row in customer_items if row.get("fullName") == marker and row.get("qqId")]
        if len(exact) != 1:
            self.add("QQL-14", "BLOCKED", f"safe UAT customer unique-match count={len(exact)}")
            return
        customer = exact[0]
        target = next((friend for friend in friends if str(friend.get("uin") or "") == str(customer.get("qqId") or "")), None)
        if not target:
            self.add("QQL-14", "BLOCKED", "safe UAT customer QQ did not match a current private session")
            return
        peer = self.friend_peer(target)
        _, preview_messages = self.fetch_messages(peer, page=1, limit=50)
        if not self.list_of(preview_messages, "messages"):
            self.add("QQL-14", "BLOCKED", "matched private session has no importable messages")
            return
        end = datetime.now(timezone.utc)
        start = end - timedelta(days=89)
        request = {"customerId": customer["id"], "chatType": peer["chatType"],
                   "peerUid": peer["peerUid"], "peerQq": peer["peerUin"],
                   "sessionName": "UAT-private-closeout", "targetSenderUid": peer["peerUid"],
                   "targetSenderQq": peer["peerUin"], "groupContextConfirmed": False,
                   "rangeStart": start.isoformat().replace("+00:00", "Z"),
                   "rangeEnd": end.isoformat().replace("+00:00", "Z"), "bindMissingQq": False}
        before = self.call("GET", f"/api/v1/customers/{customer['id']}/interactions")
        before_count = len(self.body(before) or [])
        preview = self.call("POST", "/api/v1/qq-imports/preview", body=request, timeout=180)
        if preview.status_code != 200:
            code = (self.body(preview) or {}).get("code", "") if isinstance(self.body(preview), dict) else ""
            self.add("QQL-14", "FAIL", f"private import preview HTTP={preview.status_code}; code={code}", preview)
            return
        payload = self.body(preview)
        import_id, draft = payload.get("importId"), payload.get("draft")
        self.add("QQL-14", "PASS", f"AI preview messages={payload.get('messageCount')}; structured-draft={isinstance(draft, dict)}", preview)
        if isinstance(draft, dict) and not str(draft.get("summary") or "").strip():
            draft["summary"] = "UAT 人工确认的 QQ 对话摘要"
            draft.setdefault("customerQuotes", [])
            draft.setdefault("uncertainties", ["自动化闭环使用人工确认摘要"])
        key = "uat-qq-closeout-" + uuid.uuid4().hex
        confirm_body = {"idempotencyKey": key, "draft": draft}
        first = self.call("POST", f"/api/v1/qq-imports/{import_id}/confirm", body=confirm_body)
        second = self.call("POST", f"/api/v1/qq-imports/{import_id}/confirm", body=confirm_body)
        first_body, second_body = self.body(first), self.body(second)
        first_code = first_body.get("code", "") if isinstance(first_body, dict) else ""
        second_code = second_body.get("code", "") if isinstance(second_body, dict) else ""
        same = first.status_code == second.status_code == 200 and first_body.get("interactionId") == second_body.get("interactionId")
        duplicate = self.call("POST", "/api/v1/qq-imports/preview", body=request, timeout=180)
        duplicate_body = self.body(duplicate)
        duplicate_code = duplicate_body.get("code", "") if isinstance(duplicate_body, dict) else ""
        self.add("QQL-15", "PASS" if same and duplicate.status_code == 409 else "FAIL",
                 f"confirm1={first.status_code}/{first_code}; confirm2={second.status_code}/{second_code}; idempotent={same}; duplicate={duplicate.status_code}/{duplicate_code}", second)
        raw = self.call("GET", f"/api/v1/qq-imports/{import_id}/raw", timeout=180)
        raw_payload = self.body(raw)
        raw_count = len(raw_payload.get("messages", [])) if isinstance(raw_payload, dict) else 0
        after = self.call("GET", f"/api/v1/customers/{customer['id']}/interactions")
        after_rows = self.body(after) or []
        timeline_ok = len(after_rows) == before_count + 1 and any(str(row.get("id")) == str(first_body.get("interactionId")) for row in after_rows)
        self.add("QQL-16", "PASS" if raw.status_code == 200 and raw_count > 0 and timeline_ok else "FAIL",
                 f"raw-read HTTP={raw.status_code}; messages={raw_count}; timeline-increment={timeline_ok}", raw)

    def group_crm_import(self, groups: list[dict[str, Any]]) -> None:
        marker = os.getenv("QQ_UAT_CUSTOMER_NAME", "QQUAT自动化20260814")
        response = self.call("GET", "/api/v1/customers", params={"search": marker, "size": 10})
        items = (self.body(response) or {}).get("items", []) if isinstance(self.body(response), dict) else []
        customers = [row for row in items if row.get("fullName") == marker and row.get("qqId")]
        if len(customers) != 1:
            self.add("QQL-20", "BLOCKED", "safe group-import customer was not uniquely available")
            return
        customer, target_message, target_group, target_uid = customers[0], None, None, ""
        target_qq = str(customer["qqId"])
        for group in groups[:40]:
            try:
                _, data = self.fetch_messages(self.group_peer(group), limit=50)
            except Exception:
                continue
            for message in self.list_of(data, "messages"):
                sender = message.get("sender") if isinstance(message.get("sender"), dict) else {}
                candidates = [message.get("senderUid"), message.get("senderUin"),
                              sender.get("uid"), sender.get("uin")]
                if target_qq in {str(value) for value in candidates if value not in (None, "")}:
                    target_message, target_group = message, group
                    target_uid = str(next((value for value in candidates if value not in (None, "")), target_qq))
                    break
            if target_message:
                break
        if not target_message or not target_group:
            self.add("QQL-20", "BLOCKED", "no controlled group message from the safe UAT customer in sampled recent history")
            return
        epoch_ms = int(time.time() * 1000)
        for key in ("msgTime", "time", "timestamp"):
            try:
                value = int(float(target_message.get(key)))
                epoch_ms = value if value >= 1_000_000_000_000 else value * 1000
                break
            except Exception:
                continue
        center = datetime.fromtimestamp(epoch_ms / 1000, tz=timezone.utc)
        end = min(datetime.now(timezone.utc), center + timedelta(hours=12))
        start = max(end - timedelta(days=1), center - timedelta(hours=12))
        peer = self.group_peer(target_group)
        request = {"customerId": customer["id"], "chatType": 2, "peerUid": peer["peerUid"],
                   "peerQq": peer["peerUin"], "sessionName": "UAT-group-closeout",
                   "targetSenderUid": target_uid, "targetSenderQq": target_qq,
                   "groupContextConfirmed": True,
                   "rangeStart": start.isoformat().replace("+00:00", "Z"),
                   "rangeEnd": end.isoformat().replace("+00:00", "Z"), "bindMissingQq": False}
        before = self.call("GET", f"/api/v1/customers/{customer['id']}/interactions")
        preview = self.call("POST", "/api/v1/qq-imports/preview", body=request, timeout=180)
        if preview.status_code != 200:
            body = self.body(preview)
            code = body.get("code", "") if isinstance(body, dict) else ""
            self.add("QQL-20", "BLOCKED" if code in {"QQ_MESSAGES_EMPTY", "QQ_IMPORT_TOO_LARGE"} else "FAIL",
                     f"group import preview HTTP={preview.status_code}; code={code}", preview)
            return
        payload = self.body(preview)
        draft = payload.get("draft")
        if isinstance(draft, dict) and not str(draft.get("summary") or "").strip():
            draft["summary"] = "UAT 人工确认的 QQ 群聊摘要"
            draft.setdefault("customerQuotes", [])
            draft.setdefault("uncertainties", ["群聊上下文已人工确认"])
        confirmed = self.call("POST", f"/api/v1/qq-imports/{payload['importId']}/confirm", body={
            "idempotencyKey": "uat-qq-group-" + uuid.uuid4().hex, "draft": draft,
        })
        after = self.call("GET", f"/api/v1/customers/{customer['id']}/interactions")
        timeline_ok = len(self.body(after) or []) == len(self.body(before) or []) + 1
        self.add("QQL-20", "PASS" if confirmed.status_code == 200 and timeline_ok else "FAIL",
                 f"group-context-confirm={confirmed.status_code}; timeline-increment={timeline_ok}", confirmed)

    def role_matrix(self) -> None:
        shared_password = os.getenv("QQ_UAT_SHARED_PASSWORD", self.password)
        tag = uuid.uuid4().hex[:8]
        team_response = self.call("POST", "/api/v1/admin/teams", body={"name": f"QQ-UAT-{tag}"})
        team = self.body(team_response)
        if team_response.status_code != 201 or not isinstance(team, dict):
            self.add("QQL-17", "BLOCKED", f"temporary permission-fixture team HTTP={team_response.status_code}")
            return
        self.created_team_id = str(team["id"])
        fixtures = [("TEAM_MANAGER", f"qq-uat-manager-{tag}", self.created_team_id),
                    ("MEMBER", f"qq-uat-member-{tag}", None),
                    ("VIEWER", f"qq-uat-viewer-{tag}", None)]
        probes: list[tuple[str, str, set[int], str]] = []
        for role, username, team_id in fixtures:
            created = self.call("POST", "/api/v1/admin/members", body={
                "username": username, "displayName": f"QQ UAT {role}", "initialPassword": shared_password,
                "role": role, "teamId": team_id,
            })
            body = self.body(created)
            if created.status_code != 201 or not isinstance(body, dict):
                self.add("QQL-17", "BLOCKED", f"temporary {role} fixture HTTP={created.status_code}", created)
                return
            self.created_member_ids.append(str(body["membershipId"]))
            probes.append((role, username, {403} if role == "VIEWER" else {409}, str(body["membershipId"])))

        completed, failures = 0, 0
        disabled_session: requests.Session | None = None
        disabled_id = ""
        for role, username, expected, membership_id in probes:
            session = requests.Session(); session.trust_env = False
            csrf_response = session.get(self.base + "/api/v1/auth/csrf", timeout=30)
            token = csrf_response.json().get("token", "") if csrf_response.status_code == 200 else ""
            login = session.post(self.base + "/api/v1/auth/login", json={"username": username, "password": shared_password},
                                 headers={"X-XSRF-TOKEN": token}, timeout=30)
            if login.status_code != 200:
                continue
            completed += 1
            mine = session.get(self.base + "/api/v1/qq-connections/me", timeout=30)
            bootstrap = session.get(self.base + "/api/v1/qq-workbench/bootstrap", timeout=30)
            admin = session.get(self.base + "/api/v1/admin/qq-connections", timeout=30)
            expected_mine = 403 if role == "VIEWER" else 200
            if mine.status_code != expected_mine or bootstrap.status_code not in expected or admin.status_code != 403:
                failures += 1
            if role == "MEMBER":
                disabled_session, disabled_id = session, membership_id
        disabled_ok = False
        if disabled_session and disabled_id:
            disabled = self.call("PATCH", f"/api/v1/admin/members/{disabled_id}", body={
                "displayName": "QQ UAT MEMBER", "role": "MEMBER", "status": "DISABLED", "teamId": None,
            })
            after_disable = disabled_session.get(self.base + "/api/v1/qq-connections/me", timeout=30)
            disabled_ok = disabled.status_code == 200 and after_disable.status_code in {401, 403}
        result = "PASS" if completed == len(probes) and failures == 0 and disabled_ok else \
            "BLOCKED" if completed < len(probes) else "FAIL"
        self.add("QQL-17", result,
                 f"role-logins={completed}/{len(probes)}; isolation-failures={failures}; disabled-session-rejected={disabled_ok}")

    def cleanup(self) -> None:
        for schedule_id in self.created_schedule_ids:
            self.call("DELETE", f"/api/v1/qq-workbench/qce/api/scheduled-exports/{schedule_id}")
        for task_id in self.created_task_ids:
            self.call("DELETE", f"/api/v1/qq-workbench/qce/api/tasks/{task_id}")
        for membership_id in reversed(self.created_member_ids):
            self.call("POST", f"/api/v1/admin/members/{membership_id}/delete", body={})
        if self.created_team_id:
            self.call("DELETE", f"/api/v1/admin/teams/{self.created_team_id}")

    def write_results(self) -> None:
        with (self.output / "scenario-results.csv").open("w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=["scenario_id", "status", "actual", "trace_id"])
            writer.writeheader()
            writer.writerows(asdict(row) for row in self.results)
        summary = {status: sum(row.status == status for row in self.results) for status in ("PASS", "FAIL", "BLOCKED")}
        (self.output / "summary.json").write_text(json.dumps({"total": len(self.results), **summary}, indent=2), encoding="utf-8")

    def run(self) -> int:
        try:
            self.login()
            self.security()
            self.connection()
            self.websocket()
            friends, groups, _ = self.inventory()
            private, group = self.preview_and_resources(friends, groups)
            self.group_resources(group)
            self.existing_exports()
            private_peer = self.friend_peer(private) if private else None
            self.create_export(private_peer)
            self.schedule_crud(private_peer)
            self.crm_import(friends)
            self.group_crm_import(groups)
            self.role_matrix()
        finally:
            try:
                self.cleanup()
            finally:
                self.write_results()
        return 1 if any(row.status == "FAIL" for row in self.results) else 0


def main() -> int:
    required = ("QQ_UAT_BASE_URL", "QQ_UAT_USERNAME", "QQ_UAT_PASSWORD", "QQ_UAT_OUTPUT")
    missing = [key for key in required if not os.getenv(key)]
    if missing:
        raise SystemExit("missing required environment variables: " + ", ".join(missing))
    return Suite(os.environ["QQ_UAT_BASE_URL"], os.environ["QQ_UAT_USERNAME"],
                 os.environ["QQ_UAT_PASSWORD"], Path(os.environ["QQ_UAT_OUTPUT"])).run()


if __name__ == "__main__":
    sys.exit(main())
