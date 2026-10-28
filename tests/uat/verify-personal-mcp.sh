#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 ]]; then
  printf 'Usage: %s <base-url> <environment-file>\n' "$0" >&2
  exit 2
fi

base_url=${1%/}
environment_file=$2
verification_dir=$(mktemp -d)
chmod 700 "$verification_dir"

fail() {
  printf 'personal_mcp_verification=FAIL phase=%s detail=%s\n' "$1" "$2" >&2
  exit 1
}

require_status() {
  local phase=$1
  local expected=$2
  local actual=$3
  [[ "$actual" == "$expected" ]] || fail "$phase" "expected_http_${expected}_got_${actual}"
}

cleanup() {
  if [[ -s "$verification_dir/cookies.txt" && -s "$verification_dir/csrf.conf" ]]; then
    curl --silent --show-error --output /dev/null --config "$verification_dir/csrf.conf" \
      --request POST "$base_url/api/v1/auth/logout" || true
  fi
  find "$verification_dir" -type f -exec chmod 600 {} + 2>/dev/null || true
  rm -f -- "$verification_dir"/*
  rmdir "$verification_dir"
}
trap cleanup EXIT

environment_value() {
  local key=$1
  local value
  value=$(awk -v expected="$key" 'index($0, expected "=") == 1 { sub(/^[^=]*=/, ""); print; exit }' "$environment_file")
  if [[ "$value" == \"*\" && "$value" == *\" ]]; then value=${value:1:${#value}-2}; fi
  if [[ "$value" == \'*\' && "$value" == *\' ]]; then value=${value:1:${#value}-2}; fi
  printf '%s' "$value"
}

BOOTSTRAP_ADMIN_USERNAME=$(environment_value BOOTSTRAP_ADMIN_USERNAME)
BOOTSTRAP_ADMIN_PASSWORD=$(environment_value BOOTSTRAP_ADMIN_PASSWORD)
: "${BOOTSTRAP_ADMIN_USERNAME:?BOOTSTRAP_ADMIN_USERNAME is required}"
: "${BOOTSTRAP_ADMIN_PASSWORD:?BOOTSTRAP_ADMIN_PASSWORD is required}"

jq -n --arg username "$BOOTSTRAP_ADMIN_USERNAME" --arg password "$BOOTSTRAP_ADMIN_PASSWORD" \
  '{username: $username, password: $password}' > "$verification_dir/login.json"
chmod 600 "$verification_dir/login.json"

csrf_status=$(curl --silent --show-error --output "$verification_dir/csrf.json" --write-out '%{http_code}' \
  --cookie-jar "$verification_dir/cookies.txt" --cookie "$verification_dir/cookies.txt" \
  "$base_url/api/v1/auth/csrf")
require_status csrf 200 "$csrf_status"

csrf_header=$(jq -r '.headerName' "$verification_dir/csrf.json")
csrf_token=$(jq -r '.token' "$verification_dir/csrf.json")
{
  printf 'cookie = "%s"\n' "$verification_dir/cookies.txt"
  printf 'cookie-jar = "%s"\n' "$verification_dir/cookies.txt"
  printf 'header = "%s: %s"\n' "$csrf_header" "$csrf_token"
} > "$verification_dir/csrf.conf"
chmod 600 "$verification_dir/csrf.conf"

login_status=$(curl --silent --show-error --output "$verification_dir/me.json" --write-out '%{http_code}' \
  --config "$verification_dir/csrf.conf" --header 'Content-Type: application/json' \
  --data-binary "@$verification_dir/login.json" "$base_url/api/v1/auth/login")
require_status login 200 "$login_status"

issue_status=$(curl --silent --show-error --output "$verification_dir/personal.json" --write-out '%{http_code}' \
  --config "$verification_dir/csrf.conf" --request POST "$base_url/api/v1/agent/personal-token")
require_status issue_personal_token 200 "$issue_status"

access_token=$(jq -r '.accessToken' "$verification_dir/personal.json")
grant_id=$(jq -r '.grantId' "$verification_dir/personal.json")
[[ -n "$access_token" && "$access_token" != null ]] || fail issue_personal_token missing_access_token
[[ -n "$grant_id" && "$grant_id" != null ]] || fail issue_personal_token missing_grant_id

{
  printf 'header = "Authorization: Bearer %s"\n' "$access_token"
  printf 'header = "Accept: application/json, text/event-stream"\n'
  printf 'header = "Content-Type: application/json"\n'
} > "$verification_dir/mcp.conf"
chmod 600 "$verification_dir/mcp.conf"

printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"uat-personal-token","version":"1.0"}}}' > "$verification_dir/initialize.json"
initialize_status=$(curl --silent --show-error --dump-header "$verification_dir/initialize.headers" \
  --output "$verification_dir/initialize.body" --write-out '%{http_code}' --config "$verification_dir/mcp.conf" \
  --data-binary "@$verification_dir/initialize.json" "$base_url/mcp")
require_status initialize 200 "$initialize_status"

session_id=$(awk 'BEGIN { IGNORECASE=1 } /^mcp-session-id:/ { gsub("\r", "", $2); print $2; exit }' "$verification_dir/initialize.headers")
[[ -n "$session_id" ]] || fail initialize missing_mcp_session_id
printf 'header = "mcp-session-id: %s"\n' "$session_id" >> "$verification_dir/mcp.conf"

printf '%s\n' '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' > "$verification_dir/tools.json"
tools_status=$(curl --silent --show-error --output "$verification_dir/tools.body" --write-out '%{http_code}' \
  --config "$verification_dir/mcp.conf" --data-binary "@$verification_dir/tools.json" "$base_url/mcp")
require_status tools_list 200 "$tools_status"

tools_payload=$(sed -n 's/^data:[[:space:]]*//p' "$verification_dir/tools.body" | head -n 1)
printf '%s' "$tools_payload" > "$verification_dir/tools.json.response"
jq -e '.result.tools | map(.name) | index("yingge_customer_search") != null' "$verification_dir/tools.json.response" >/dev/null \
  || fail tools_list missing_customer_search
jq -e '.result.tools | map(.name) | index("yingge_admin_members") != null' "$verification_dir/tools.json.response" >/dev/null \
  || fail tools_list missing_admin_members
tool_count=$(jq '.result.tools | length' "$verification_dir/tools.json.response")

revoke_status=$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
  --config "$verification_dir/csrf.conf" --request DELETE "$base_url/api/v1/agent/authorizations/$grant_id")
require_status revoke 204 "$revoke_status"

revoked_status=$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
  --config "$verification_dir/mcp.conf" --data-binary "@$verification_dir/initialize.json" "$base_url/mcp")
require_status revoked_token 401 "$revoked_status"

printf 'personal_mcp_verification=PASS login=%s csrf=%s issue=%s initialize=%s tools=%s tool_count=%s revoke=%s revoked_token=%s\n' \
  "$login_status" "$csrf_status" "$issue_status" "$initialize_status" "$tools_status" "$tool_count" "$revoke_status" "$revoked_status"
