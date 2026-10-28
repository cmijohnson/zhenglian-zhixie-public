#!/bin/sh
set -eu

backup_dir="${BACKUP_DIR:-/backups}"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$backup_dir/daily" "$backup_dir/weekly"

daily_file="$backup_dir/daily/yingge-${timestamp}.dump"
pg_dump --format=custom --compress=9 --file="$daily_file"
pg_restore --list "$daily_file" >/dev/null
(
    cd "$(dirname "$daily_file")"
    sha256sum "$(basename "$daily_file")" >"$(basename "$daily_file").sha256"
)

find "$backup_dir/daily" -type f -mtime +14 -delete

weekday="$(date -u +%u)"
if [ "$weekday" = "7" ]; then
    weekly_file="$backup_dir/weekly/yingge-${timestamp}.dump"
    cp "$daily_file" "$weekly_file"
    (
        cd "$(dirname "$weekly_file")"
        sha256sum "$(basename "$weekly_file")" >"$(basename "$weekly_file").sha256"
    )
    find "$backup_dir/weekly" -type f -mtime +56 -delete
fi

echo "Backup verified: $daily_file"
