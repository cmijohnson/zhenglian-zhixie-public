#!/usr/bin/env bash
set -euo pipefail

contract="${1:-contracts/mobile-api.yaml}"

test -s "$contract"

required_tokens=(
  "openapi: 3.1.0"
  "/api/v1/mobile/auth/login:"
  "/api/v1/mobile/auth/register:"
  "/api/v1/mobile/auth/registration-options:"
  "/api/v1/auth/password:"
  "/api/v1/mobile/auth/refresh:"
  "/api/v1/mobile/auth/logout:"
  "/api/v1/mobile/auth/devices:"
  "/api/v1/mobile/bootstrap:"
  "/api/v1/mobile/devices/push-registration:"
  "/api/v1/mobile/web-sessions:"
  "/api/v1/mobile/app-releases/current:"
  "/api/v1/customers/{customerId}/deal-requests:"
  "/api/v1/admin/deal-requests/{requestId}/approve:"
  "/api/v1/admin/deal-requests/{requestId}/reject:"
  "enum: [NONE, PENDING, WON]"
  "enum: [PENDING, APPROVED, REJECTED]"
  "Idempotency-Key"
  "X-Yingge-Version-Code"
  "x-mobile-bearer-required-headers"
  "FEATURE_RETIRED"
)

for token in "${required_tokens[@]}"; do
  if ! grep -Fq "$token" "$contract"; then
    printf 'mobile contract missing required token: %s\n' "$token" >&2
    exit 1
  fi
done

if grep -Eiq '(api[_-]?key|client[_-]?secret|password:[[:space:]]+[^{$]|token:[[:space:]]+[A-Za-z0-9+/=_-]{20,})' "$contract"; then
  printf 'mobile contract appears to contain a literal credential\n' >&2
  exit 1
fi

printf 'mobile contract invariant check passed: %s\n' "$contract"
