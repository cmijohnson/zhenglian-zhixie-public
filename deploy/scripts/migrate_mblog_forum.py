#!/usr/bin/env python3
"""Generate idempotent PostgreSQL SQL for the legacy MBlog SQLite database."""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import pathlib
import re
import sqlite3
import uuid

NAMESPACE = uuid.UUID("d58f4cc3-3d5a-4f66-bae7-95a45d5f3d7a")


def sql_text(value: object | None) -> str:
    if value is None:
        return "NULL"
    return "'" + str(value).replace("'", "''") + "'"


def sql_time(value: object | None) -> str:
    if value is None:
        return "now()"
    if isinstance(value, (int, float)) or str(value).isdigit():
        number = int(value)
        if number > 10_000_000_000:
            number /= 1000
        instant = dt.datetime.fromtimestamp(number, tz=dt.timezone.utc)
        return sql_text(instant.isoformat())
    raw = str(value).strip().replace(" ", "T")
    if not re.search(r"[zZ]|[+-]\d\d:\d\d$", raw):
        raw += "+08:00"
    return sql_text(raw)


def title_for(content: str, memo_id: int) -> str:
    first = next((line.strip() for line in content.splitlines() if line.strip()), "")
    first = re.sub(r"^#{1,6}\s*", "", first)
    if re.fullmatch(r"https?://\S+", first):
        return "共享资源"
    return (first[:72] or f"历史内容 #{memo_id}").strip()


def excerpt_for(content: str) -> str:
    plain = re.sub(r"\s+", " ", re.sub(r"[#*_`>~-]", "", content)).strip()
    return plain[:217] + "…" if len(plain) > 220 else plain


def stable(kind: str, legacy_id: object) -> uuid.UUID:
    return uuid.uuid5(NAMESPACE, f"{kind}:{legacy_id}")


def membership_expr(username: str) -> str:
    return (
        "(SELECT m.id FROM memberships m "
        "JOIN user_accounts u ON u.id = m.user_id "
        "JOIN primary_org o ON o.id = m.organization_id "
        f"WHERE u.username = {sql_text(username)} AND m.status = 'ACTIVE' LIMIT 1)"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("sqlite_path")
    parser.add_argument("output_sql")
    args = parser.parse_args()

    source = pathlib.Path(args.sqlite_path).resolve()
    output = pathlib.Path(args.output_sql).resolve()
    connection = sqlite3.connect(f"file:{source}?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    users = {row["id"]: row for row in connection.execute("SELECT * FROM t_user")}
    memos = list(connection.execute("SELECT * FROM t_memo ORDER BY id"))
    comments = list(connection.execute("SELECT * FROM t_comment ORDER BY id"))
    resources = list(connection.execute("SELECT * FROM t_resource WHERE memo_id > 0 ORDER BY public_id"))
    likes = list(connection.execute(
        "SELECT * FROM t_user_memo_relation WHERE fav_type = 'LIKE' ORDER BY id"
    ))

    lines = [
        "BEGIN;",
        "CREATE TEMP TABLE primary_org AS "
        "SELECT id FROM organizations WHERE status = 'ACTIVE' ORDER BY created_at LIMIT 1;",
        "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM primary_org) "
        "THEN RAISE EXCEPTION 'No active organization'; END IF; END $$;",
    ]
    for memo in memos:
        user = users.get(memo["user_id"])
        username = user["username"] if user else ""
        display_name = (user["display_name"] or username) if user else "历史成员"
        content = memo["content"] or ""
        post_id = stable("post", memo["id"])
        post_type = "ANNOUNCEMENT" if memo["priority"] and int(memo["priority"]) > 0 else "POST"
        status = "PUBLISHED" if memo["status"] == "NORMAL" else "HIDDEN"
        lines.append(
            "INSERT INTO forum_posts "
            "(id, organization_id, board_id, author_membership_id, author_display_name, "
            "post_type, title, content, excerpt, status, pinned, locked, view_count, "
            "legacy_source, legacy_id, published_at, created_at, updated_at) "
            f"SELECT '{post_id}', o.id, b.id, {membership_expr(username)}, "
            f"{sql_text(display_name)}, {sql_text(post_type)}, {sql_text(title_for(content, memo['id']))}, "
            f"{sql_text(content)}, {sql_text(excerpt_for(content))}, {sql_text(status)}, "
            f"{'true' if memo['priority'] and int(memo['priority']) > 0 else 'false'}, "
            f"{'true' if not memo['enable_comment'] else 'false'}, {int(memo['view_count'] or 0)}, "
            f"'mblog', {sql_text(str(memo['id']))}, {sql_time(memo['created'])}, "
            f"{sql_time(memo['created'])}, {sql_time(memo['updated'] or memo['created'])} "
            "FROM primary_org o JOIN forum_boards b ON b.organization_id = o.id AND b.slug = 'sharing' "
            "ON CONFLICT (legacy_source, legacy_id) WHERE legacy_source IS NOT NULL AND legacy_id IS NOT NULL "
            "DO NOTHING;"
        )
        raw_tags = memo["tags"] or ""
        for tag in dict.fromkeys(part.strip("# \t") for part in re.split(r"[,，\s]+", raw_tags) if part.strip("# \t")):
            lines.append(
                "INSERT INTO forum_post_tags (post_id, tag) "
                f"VALUES ('{post_id}', {sql_text(tag[:48])}) ON CONFLICT DO NOTHING;"
            )

    for comment in comments:
        user = users.get(comment["user_id"])
        username = user["username"] if user else ""
        display_name = comment["user_name"] or ((user["display_name"] or username) if user else "历史成员")
        lines.append(
            "INSERT INTO forum_comments "
            "(id, post_id, author_membership_id, author_display_name, content, status, "
            "legacy_source, legacy_id, created_at, updated_at) "
            f"VALUES ('{stable('comment', comment['id'])}', '{stable('post', comment['memo_id'])}', "
            f"{membership_expr(username)}, {sql_text(display_name)}, {sql_text(comment['content'])}, "
            f"{sql_text('PUBLISHED' if int(comment['approved'] or 0) >= 0 else 'HIDDEN')}, "
            f"'mblog', {sql_text(str(comment['id']))}, {sql_time(comment['created'])}, "
            f"{sql_time(comment['updated'] or comment['created'])}) "
            "ON CONFLICT (legacy_source, legacy_id) WHERE legacy_source IS NOT NULL AND legacy_id IS NOT NULL "
            "DO NOTHING;"
        )

    for like in likes:
        user = users.get(like["user_id"])
        if not user:
            continue
        lines.append(
            "INSERT INTO forum_likes (post_id, membership_id, created_at) "
            f"SELECT '{stable('post', like['memo_id'])}', m.id, {sql_time(like['created'])} "
            "FROM memberships m JOIN user_accounts u ON u.id = m.user_id "
            "JOIN primary_org o ON o.id = m.organization_id "
            f"WHERE u.username = {sql_text(user['username'])} AND m.status = 'ACTIVE' "
            "ON CONFLICT DO NOTHING;"
        )

    for resource in resources:
        path = pathlib.Path(resource["internal_path"] or "")
        external = resource["external_link"]
        if path.is_file():
            payload = base64.b64encode(path.read_bytes()).decode("ascii")
            content = f"decode('{payload}', 'base64')"
            external_sql = "NULL"
        elif external and re.match(r"https?://", external):
            content = "NULL"
            external_sql = sql_text(external)
        else:
            continue
        lines.append(
            "INSERT INTO forum_attachments "
            "(id, post_id, file_name, content_type, file_size, content, external_url, "
            "legacy_source, legacy_id, created_at) "
            f"VALUES ('{stable('attachment', resource['public_id'])}', "
            f"'{stable('post', resource['memo_id'])}', {sql_text(resource['file_name'])}, "
            f"{sql_text(resource['file_type'] or 'application/octet-stream')}, {int(resource['size'])}, "
            f"{content}, {external_sql}, 'mblog', {sql_text(resource['public_id'])}, "
            f"{sql_time(resource['created'])}) "
            "ON CONFLICT (legacy_source, legacy_id) WHERE legacy_source IS NOT NULL AND legacy_id IS NOT NULL "
            "DO NOTHING;"
        )

    lines.extend([
        "DROP TABLE primary_org;",
        "COMMIT;",
        (
            "\\echo 'MBlog migration complete: "
            f"{len(memos)} posts, {len(comments)} comments, {len(resources)} post attachments, "
            f"{len(likes)} likes discovered'"
        ),
    ])
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(
        f"Generated {output}: {len(memos)} posts, {len(comments)} comments, "
        f"{len(resources)} post attachments, {len(likes)} likes"
    )


if __name__ == "__main__":
    main()
