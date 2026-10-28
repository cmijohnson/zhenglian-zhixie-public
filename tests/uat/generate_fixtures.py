#!/usr/bin/env python3
"""Generate deterministic, synthetic UAT import and legacy-migration fixtures."""

from __future__ import annotations

import csv
import hashlib
import json
import os
import random
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path

from openpyxl import Workbook


SEED = 20260720
ROOT = Path(__file__).resolve().parent
OUT = ROOT / "fixtures"
HEADERS = [
    "姓名", "昵称", "手机号", "微信号", "QQ号", "学校", "专业", "年级", "城市",
    "身份类型", "关注方向", "需求摘要", "报名意向", "紧急程度", "核实状态",
    "来源渠道", "当前状态", "内部备注",
]


def customer_row(index: int, *, invalid: bool = False) -> list[str]:
    name = "" if invalid and index % 2 == 0 else f"UAT-20260720-导入客户-{index:05d}"
    phone = "" if invalid else f"17{index:09d}"
    note = {
        1: "'=2+3",
        2: "'+SUM(1,1)",
        3: "'@external-reference",
        4: "'-10+20",
    }.get(index, f"固定 seed={SEED} 合成数据")
    return [
        name, f"导入昵称{index}", phone, "", "", f"UAT大学{index % 40 + 1}",
        f"专业{index % 30 + 1}", "26级" if index % 5 == 0 else "25级",
        f"城市{index % 30 + 1}", "在校生", "就业|升学", "批量导入验收",
        ["低", "中", "高"][index % 3], ["低", "中", "高"][(index + 1) % 3],
        ["未核实", "已核实", "部分待确认"][index % 3], "活动", "初次接触", note,
    ]


def write_csv(path: Path, rows: list[list[str]], *, bom: bool = False) -> None:
    encoding = "utf-8-sig" if bom else "utf-8"
    with path.open("w", newline="", encoding=encoding) as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(HEADERS)
        writer.writerows(rows)


def write_xlsx(path: Path, rows: list[list[str]]) -> None:
    workbook = Workbook(write_only=True)
    sheet = workbook.create_sheet("客户导入")
    sheet.append(HEADERS)
    for row in rows:
        sheet.append(row)
    workbook.save(path)


def create_sqlite(path: Path) -> None:
    if path.exists():
        path.chmod(0o600)
        path.unlink()
    connection = sqlite3.connect(path)
    connection.execute("PRAGMA foreign_keys=ON")
    connection.executescript(
        """
        CREATE TABLE student_profiles (
          id INTEGER PRIMARY KEY, full_name TEXT NOT NULL, nickname TEXT, phone TEXT,
          wechat_id TEXT, qq_id TEXT, school TEXT, major TEXT, grade TEXT, city TEXT,
          identity_type TEXT, focus_directions TEXT, demand_summary TEXT,
          intention_level TEXT, urgency_level TEXT, verification_status TEXT,
          source_channel TEXT, current_status TEXT, owner_notes TEXT,
          created_at TEXT, updated_at TEXT, last_contact_at TEXT,
          next_follow_up_at TEXT, is_archived INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE interaction_logs (
          id INTEGER PRIMARY KEY, student_profile_id INTEGER NOT NULL REFERENCES student_profiles(id),
          contacted_at TEXT, channel TEXT, summary TEXT, details TEXT, outcome TEXT,
          verification_todo TEXT, next_follow_up_at TEXT, created_at TEXT
        );
        CREATE TABLE tags (
          id INTEGER PRIMARY KEY, name TEXT NOT NULL, category TEXT, color TEXT, is_system INTEGER
        );
        CREATE TABLE profile_tags (
          student_profile_id INTEGER NOT NULL REFERENCES student_profiles(id),
          tag_id INTEGER NOT NULL REFERENCES tags(id), PRIMARY KEY(student_profile_id, tag_id)
        );
        CREATE TABLE saved_filters (
          id INTEGER PRIMARY KEY, name TEXT NOT NULL, filters_json TEXT NOT NULL,
          sort_by TEXT, show_on_homepage INTEGER, created_at TEXT
        );
        CREATE TABLE reminder_events (
          id INTEGER PRIMARY KEY, student_profile_id INTEGER NOT NULL REFERENCES student_profiles(id),
          interaction_log_id INTEGER REFERENCES interaction_logs(id), remind_at TEXT,
          title TEXT, status TEXT, notification_sent INTEGER, created_at TEXT, updated_at TEXT
        );
        CREATE TABLE app_settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        """
    )
    connection.execute("INSERT INTO app_settings VALUES (?, ?)", ("defaultReminderTime", "09:00"))
    connection.executemany(
        "INSERT INTO tags VALUES (?, ?, ?, ?, ?)",
        [(i, f"UAT迁移标签{i:02d}", "迁移", "#2563eb", 0) for i in range(1, 21)],
    )
    connection.executemany(
        "INSERT INTO saved_filters VALUES (?, ?, ?, ?, ?, ?)",
        [
            (i, f"UAT保存筛选{i:02d}", json.dumps({"grade": "26级", "index": i}, ensure_ascii=False),
             "updatedAt", i % 2, "2026-07-15T09:00:00+08:00")
            for i in range(1, 21)
        ],
    )

    base = datetime(2026, 7, 15, 1, 0, tzinfo=timezone.utc)
    customers: list[tuple[object, ...]] = []
    interactions: list[tuple[object, ...]] = []
    reminders: list[tuple[object, ...]] = []
    links: list[tuple[int, int]] = []
    for index in range(1, 20_001):
        created = base + timedelta(minutes=index % 5_000)
        updated = created + timedelta(hours=1)
        customers.append(
            (
                index, f"UAT-20260720-迁移客户-{index:05d}", f"迁移昵称{index}",
                f"18{index:09d}", f"legacy_wx_{index}" if index % 3 == 0 else None,
                str(900_000_000 + index) if index % 5 == 0 else None,
                f"迁移大学{index % 40 + 1}", f"迁移专业{index % 30 + 1}",
                "26级" if index % 5 == 0 else "25级", f"迁移城市{index % 30 + 1}",
                "在校生", json.dumps(["就业", "升学"], ensure_ascii=False),
                "SQLite 只读迁移正式规模样本", ["低", "中", "高"][index % 3],
                ["低", "中", "高"][(index + 1) % 3], "已核实", "活动", "跟进中",
                f"原始 QQ/时间保留样本 {index}", created.isoformat(), updated.isoformat(),
                updated.isoformat(), (updated + timedelta(days=2)).isoformat(), int(index % 10 == 0),
            )
        )
        links.append((index, index % 20 + 1))
        for offset in range(2):
            interaction_id = (index - 1) * 2 + offset + 1
            contacted = updated + timedelta(minutes=offset * 5)
            interactions.append(
                (
                    interaction_id, index, contacted.isoformat(), "微信" if offset == 0 else "电话",
                    f"UAT迁移沟通-{interaction_id}", "迁移沟通详情", "继续跟进", "核实专业",
                    (contacted + timedelta(days=2)).isoformat(), contacted.isoformat(),
                )
            )
        reminders.append(
            (
                index, index, (index - 1) * 2 + 1, (updated + timedelta(days=2)).isoformat(),
                f"UAT迁移提醒-{index}", ["pending", "completed", "cancelled"][index % 3],
                int(index % 2 == 0), created.isoformat(), updated.isoformat(),
            )
        )
        if index % 500 == 0:
            connection.executemany(
                "INSERT INTO student_profiles VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", customers
            )
            connection.executemany("INSERT INTO profile_tags VALUES (?,?)", links)
            connection.executemany("INSERT INTO interaction_logs VALUES (?,?,?,?,?,?,?,?,?,?)", interactions)
            connection.executemany("INSERT INTO reminder_events VALUES (?,?,?,?,?,?,?,?,?)", reminders)
            connection.commit()
            customers.clear(); interactions.clear(); reminders.clear(); links.clear()

    check = connection.execute("PRAGMA quick_check").fetchone()[0]
    if check.lower() != "ok":
        raise RuntimeError(f"SQLite quick_check failed: {check}")
    if connection.execute("PRAGMA foreign_key_check").fetchone() is not None:
        raise RuntimeError("SQLite foreign_key_check failed")
    connection.execute("VACUUM")
    connection.close()
    path.chmod(0o444)


def checksum_manifest() -> None:
    records = []
    for path in sorted(OUT.iterdir()):
        if path.name == "SHA256SUMS":
            continue
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        records.append(f"{digest}  {path.name}")
    (OUT / "SHA256SUMS").write_text("\n".join(records) + "\n", encoding="utf-8")


def main() -> None:
    random.seed(SEED)
    OUT.mkdir(parents=True, exist_ok=True)
    write_csv(OUT / "customers-10.csv", [customer_row(i) for i in range(1, 11)])
    rows_220 = [customer_row(i) for i in range(1, 211)] + [customer_row(i, invalid=True) for i in range(211, 221)]
    write_csv(OUT / "customers-220-bom.csv", rows_220, bom=True)
    rows_5000 = [customer_row(i) for i in range(1, 4_981)] + [customer_row(i, invalid=True) for i in range(4_981, 5_001)]
    write_csv(OUT / "customers-5000.csv", rows_5000, bom=True)
    write_xlsx(OUT / "customers-10.xlsx", [customer_row(i) for i in range(1, 11)])
    write_xlsx(OUT / "customers-5000.xlsx", rows_5000)
    (OUT / "empty.csv").write_bytes(b"")
    (OUT / "corrupted.xlsx").write_bytes(b"PK\x03\x04not-a-valid-xlsx")
    (OUT / "wrong-extension.txt").write_text("not a supported import", encoding="utf-8")
    with (OUT / "oversized-26mb.csv").open("wb") as handle:
        handle.write((",".join(HEADERS) + "\n").encode("utf-8"))
        chunk = ("UAT-oversize," + "x" * 4090 + "\n").encode("utf-8")
        while handle.tell() <= 26 * 1024 * 1024:
            handle.write(chunk)
    create_sqlite(OUT / "legacy-uat-20260720.sqlite")
    checksum_manifest()
    print(json.dumps({"seed": SEED, "fixtures": len(list(OUT.iterdir()))}, ensure_ascii=False))


if __name__ == "__main__":
    main()
