#!/usr/bin/env bash
# Edge smoke: exercise Kong's phase-4 JWT validation end to end against a running stack
# (Slice 5e phase-4). Parameterized base URL — :8000 works for BOTH compose and k3d. Drives the
# contract's Kong matrix (api_contracts.md "Kong edge validation" + "Contract-to-test matrix") as
# curl checks with EXACT status + body, mints the negative tokens itself (no fixtures), is
# non-interactive, and exits non-zero on the FIRST failed row with an evidence line fit for a
# verbatim gate record.
#
# Usage:
#   infra/scripts/edge-smoke.sh [--base-url URL] [--private-key PEM] [--iss ISS] [--kid KID]
#
# The Kong-matching PRIVATE key is needed ONLY to mint the "expired but validly-signed" token
# (Kong checks the signature before exp, so a wrong key would surface as Invalid signature, not
# token expired). It is auto-discovered from the compose / k3d key dirs; pass --private-key to be
# explicit or when both deployments' keys exist. Without it, the expired row is SKIPPED (flagged),
# never silently passed.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

BASE_URL="http://localhost:8000"
PRIV=""
ISS="user-service"
KID="user-rs256-2026-07"
TIMEOUT=10

while [ "$#" -gt 0 ]; do
  case "$1" in
    --base-url)    BASE_URL=$2; shift 2 ;;
    --private-key) PRIV=$2;     shift 2 ;;
    --iss)         ISS=$2;      shift 2 ;;
    --kid)         KID=$2;      shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

# Auto-discover the deployment's private key (compose first, then k3d local overlay).
if [ -z "$PRIV" ]; then
  for c in "$INFRA_DIR/keys/jwt-rs256-private.pem" \
           "$INFRA_DIR/k8s/overlays/local/keys/jwt-rs256-private.pem"; do
    [ -f "$c" ] && { PRIV=$c; break; }
  done
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
BODYFILE="$WORK/body"
# Throwaway key for the wrong-signature / wrong-iss rows (Kong never checks the sig of an unknown
# iss, and must reject a good-iss token signed by the wrong key).
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$WORK/wrong.pem" 2>/dev/null

FAILED=0
b64url() { openssl base64 -e -A | tr '+/' '-_' | tr -d '='; }
now() { date +%s; }

mint_rs256() { # priv iss exp
  local h p si
  h=$(printf '{"alg":"RS256","typ":"JWT","kid":"%s"}' "$KID" | b64url)
  p=$(printf '{"sub":"edge-smoke","iss":"%s","iat":%s,"exp":%s}' "$2" "$(now)" "$3" | b64url)
  si="$h.$p"
  printf '%s.%s' "$si" "$(printf '%s' "$si" | openssl dgst -sha256 -sign "$1" -binary | b64url)"
}
mint_hs256() { # secret iss exp
  local h p si
  h=$(printf '{"alg":"HS256","typ":"JWT"}' | b64url)
  p=$(printf '{"sub":"edge-smoke","iss":"%s","iat":%s,"exp":%s}' "$2" "$(now)" "$3" | b64url)
  si="$h.$p"
  printf '%s.%s' "$si" "$(printf '%s' "$si" | openssl dgst -sha256 -hmac "$1" -binary | b64url)"
}

# req METHOD PATH [curl args...] -> sets CODE, BODY
req() {
  local method=$1 path=$2; shift 2
  CODE=$(curl -sS -m "$TIMEOUT" -o "$BODYFILE" -w '%{http_code}' \
          -X "$method" "$@" "$BASE_URL$path" 2>/dev/null) || CODE="000"
  BODY=$(cat "$BODYFILE" 2>/dev/null || true)
}

norm() { printf '%s' "$1" | tr -d ' \t\n\r'; }
fail() { printf '[FAIL] %s\n' "$*" >&2; FAILED=1; exit 1; }

expect_code() { # label want
  [ "$CODE" = "$2" ] && printf '[PASS] %-38s HTTP %s\n' "$1" "$CODE" \
    || fail "$1: want HTTP $2, got $CODE  body=$BODY"
}
expect_not_401() { # label
  [ "$CODE" != "401" ] && printf '[PASS] %-38s HTTP %s (edge passed, not 401)\n' "$1" "$CODE" \
    || fail "$1: got 401 at the edge  body=$BODY"
}
expect_2xx() { # label
  case "$CODE" in 2??) printf '[PASS] %-38s HTTP %s\n' "$1" "$CODE" ;;
                  *) fail "$1: want 2xx, got $CODE  body=$BODY" ;; esac
}
expect_body() { # label want
  [ "$(norm "$BODY")" = "$(norm "$2")" ] \
    && printf '       %-38s body=%s\n' "$1" "$BODY" \
    || fail "$1: body mismatch  want=$2  got=$BODY"
}

echo "== edge-smoke against $BASE_URL =="
echo "-- Phase A: edge-only rows (no login needed) --"

# products-read stays public on GET (both paths).
req GET /api/v1/products;   expect_2xx "products-read GET /products"
req GET /api/v1/categories; expect_2xx "products-read GET /categories"

# Protected route without a token -> Kong 401 Unauthorized.
req GET /api/v1/orders; expect_code "no-token /orders" 401; expect_body "no-token /orders" '{"message":"Unauthorized"}'

# Token supplied only as ?jwt= (uri_param_names: []) -> not read -> 401.
req GET "/api/v1/orders?jwt=$(mint_rs256 "$WORK/wrong.pem" "$ISS" "$(( $(now) + 300 ))")"
expect_code "?jwt= only /orders" 401; expect_body "?jwt= only /orders" '{"message":"Unauthorized"}'

# CORS preflight to a protected route must NOT be 401 (run_on_preflight: false + cors short-circuit).
req OPTIONS /api/v1/orders -H 'Origin: https://shop.example.com' -H 'Access-Control-Request-Method: GET'
expect_not_401 "OPTIONS preflight /orders"

# Webhook block survives (request-termination 404) in any casing.
req POST /api/v1/payments/webhook -H 'Content-Type: application/json' -d '{}'
expect_code "webhook block lowercase" 404
req POST /api/v1/payments/WEBHOOK -H 'Content-Type: application/json' -d '{}'
expect_code "webhook block UPPERCASE" 404

# Wrong iss -> no credential selected (sig never checked).
req GET /api/v1/users/me -H "Authorization: Bearer $(mint_rs256 "$WORK/wrong.pem" "nobody-service" "$(( $(now) + 300 ))")"
expect_code "wrong-iss token" 401
expect_body "wrong-iss token" "{\"message\":\"No credentials found for given 'iss'\"}"

# Good iss, wrong signing key -> Invalid signature.
req GET /api/v1/users/me -H "Authorization: Bearer $(mint_rs256 "$WORK/wrong.pem" "$ISS" "$(( $(now) + 300 ))")"
expect_code "tampered-signature token" 401; expect_body "tampered-signature token" '{"message":"Invalid signature"}'

# Legacy HS256 token -> 401, never 2xx (Kong enforces the credential's RS256 algorithm). The body
# ("Invalid algorithm") is a 5th Kong-native shape; the contract pins only the status here.
req GET /api/v1/users/me -H "Authorization: Bearer $(mint_hs256 'any-burned-secret' "$ISS" "$(( $(now) + 300 ))")"
expect_code "legacy HS256 token" 401; expect_body "legacy HS256 token (record-only)" '{"message":"Invalid algorithm"}'

# Expired but validly signed -> exp claim-check shape. Needs the Kong-matching private key.
if [ -n "$PRIV" ]; then
  req GET /api/v1/users/me -H "Authorization: Bearer $(mint_rs256 "$PRIV" "$ISS" "$(( $(now) - 100 ))")"
  expect_code "expired RS256 token" 401; expect_body "expired RS256 token" '{"exp":"token expired"}'
else
  printf '[SKIP] %-38s (no deployment private key — pass --private-key)\n' "expired RS256 token"
fi

echo "-- Phase B: e2e login -> RS256 -> authenticated call --"
EMAIL="edge-smoke-$(now)-$RANDOM@example.com"
PW="SmokeTest12345"

# Tokenless auth routes reach the service (its own response, not a Kong 401).
req POST /api/v1/auth/register -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PW\",\"name\":\"Edge Smoke\"}"
case "$CODE" in 201|409) printf '[PASS] %-38s HTTP %s (auth route reached service)\n' "register" "$CODE" ;;
                *) fail "register: want 201/409, got $CODE  body=$BODY" ;; esac

req POST /api/v1/auth/login -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PW\"}"
expect_code "login" 200
TOKEN=$(python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])' < "$BODYFILE") \
  || fail "login: could not parse access_token from $BODY"
printf '       %-38s access_token acquired (RS256)\n' "login"

# Valid RS256 through the edge -> reaches the service (200 profile).
req GET /api/v1/users/me -H "Authorization: Bearer $TOKEN"
expect_2xx "valid RS256 GET /users/me"

# D4 e2e: authenticated call through the newly split products-write route. A normal user cannot
# create products, so 4xx from the SERVICE is fine — the point is the edge did NOT 401 (token
# accepted, request routed to product-service).
req POST /api/v1/products -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}'
expect_not_401 "e2e products-write (D4)"

echo
echo "NOTE: two contract rows are service-side, not black-box assertable here — verified at the"
echo "      G-edge gate: (a) the service receives the IDENTICAL Authorization: Bearer header;"
echo "      (b) X-Consumer-* headers never reach the service (global request-transformer strip)."
echo
[ "$FAILED" -eq 0 ] && echo "== edge-smoke: ALL ASSERTED ROWS PASSED ==" || echo "== edge-smoke: FAILED =="
exit "$FAILED"
