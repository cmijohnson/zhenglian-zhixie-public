#!/bin/sh
set -eu

# A named Docker volume is created as root:root on first use. Repair only the
# retention directory itself, then drop privileges before starting Java.
mkdir -p /data/qq-imports
chown app:app /data/qq-imports
chmod 0700 /data/qq-imports

exec su-exec app java -XX:MaxRAMPercentage=75 -jar /app/app.jar
