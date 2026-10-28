#!/bin/sh
set -eu

revision="58fc533f1bfdbf4535be0f83af4e595d4d7ecf27"
image="${QCE_IMAGE:-yingge/napcat-qce:${revision}}"
runtime_base_image="${QCE_RUNTIME_BASE_IMAGE:-mlikiowa/napcat-docker:latest}"
cargo_registry_index="${QCE_CARGO_REGISTRY_INDEX:-sparse+https://rsproxy.cn/index/}"
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT INT TERM

run_git() {
  if command -v git >/dev/null 2>&1; then
    git "$@"
  else
    docker run --rm -v "$workdir:$workdir" -w "$workdir" alpine/git:latest "$@"
  fi
}

run_patch() {
  if command -v node >/dev/null 2>&1; then
    node "$script_dir/../qce-patches/apply.mjs" "$workdir/qq-chat-exporter"
  else
    docker run --rm \
      -v "$workdir:$workdir" \
      -v "$script_dir/../qce-patches:/yingge-patches:ro" \
      -w "$workdir" \
      node:22-alpine node /yingge-patches/apply.mjs "$workdir/qq-chat-exporter"
  fi
}

run_git clone --filter=blob:none https://github.com/shuakami/qq-chat-exporter.git "$workdir/qq-chat-exporter"
run_git -C "$workdir/qq-chat-exporter" checkout --detach "$revision"
test "$(run_git -C "$workdir/qq-chat-exporter" rev-parse HEAD)" = "$revision"
test -f "$workdir/qq-chat-exporter/LICENSE"
run_patch
test -f "$workdir/qq-chat-exporter/YINGGE-MODIFICATIONS.md"
if [ -n "${QCE_SOURCE_ARCHIVE_DIR:-}" ]; then
  mkdir -p "$QCE_SOURCE_ARCHIVE_DIR"
  tar -czf "$QCE_SOURCE_ARCHIVE_DIR/qq-chat-exporter-${revision}-yingge-source.tar.gz" \
    -C "$workdir" qq-chat-exporter
fi
docker build --label "org.opencontainers.image.source=https://github.com/shuakami/qq-chat-exporter" \
  --label "org.opencontainers.image.revision=$revision" \
  --label "org.opencontainers.image.licenses=GPL-3.0" \
  --label "com.yingge.qq-workbench.modified=true" \
  --label "com.yingge.qq-workbench.runtime-base=$runtime_base_image" \
  --build-arg "QCE_RUNTIME_BASE_IMAGE=$runtime_base_image" \
  --build-arg "QCE_CARGO_REGISTRY_INDEX=$cargo_registry_index" \
  -f "$workdir/qq-chat-exporter/docker/Dockerfile" \
  -t "$image" "$workdir/qq-chat-exporter"

printf 'Built %s from pinned revision %s\n' "$image" "$revision"
