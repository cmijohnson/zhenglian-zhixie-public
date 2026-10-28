#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  printf 'Usage: %s <backend-image>\n' "$0" >&2
  exit 2
fi

image=$1
probe_volume="yingge-qq-storage-probe-${RANDOM}-$$"

cleanup() {
  docker volume rm "$probe_volume" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker image inspect "$image" >/dev/null
docker volume create "$probe_volume" >/dev/null

result=$(docker run --rm \
  --volume "$probe_volume:/data/qq-imports" \
  --entrypoint /bin/sh \
  "$image" -lc '
    set -eu
    identity=$(id -u):$(id -g)
    owner=$(stat -c "%u:%g" /data/qq-imports)
    mode=$(stat -c "%a" /data/qq-imports)
    test "$identity" = "100:101"
    test "$owner" = "$identity"
    test "$mode" = "700"
    printf encrypted-payload-probe > /data/qq-imports/probe.qce
    test "$(cat /data/qq-imports/probe.qce)" = encrypted-payload-probe
    rm -f /data/qq-imports/probe.qce
    printf "identity=%s owner=%s mode=%s\n" "$identity" "$owner" "$mode"
  ')

printf 'qq_storage_image_verification=PASS %s\n' "$result"
