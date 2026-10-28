#!/bin/sh
set -eu

model="${EMBEDDING_MODEL:-bge-m3}"

ollama serve &
server_pid=$!

stop_server() {
  kill -TERM "$server_pid" 2>/dev/null || true
  wait "$server_pid" 2>/dev/null || true
}
trap stop_server INT TERM

until ollama list >/dev/null 2>&1; do
  if ! kill -0 "$server_pid" 2>/dev/null; then
    wait "$server_pid"
    exit 1
  fi
  sleep 1
done

if ! ollama show "$model" >/dev/null 2>&1; then
  ollama pull "$model"
fi

touch /tmp/embedding-ready
wait "$server_pid"
