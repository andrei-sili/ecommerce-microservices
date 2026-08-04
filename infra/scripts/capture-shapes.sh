#!/usr/bin/env bash
# Capture the JSON SHAPE (key -> type, recursively) of every response the contract pins, so the
# Boot 4.x migration can be diffed structurally rather than by eyeballing bodies.
#
# Values change per run (ids, timestamps); the SHAPE must not. Each line is `path:type`, sorted, so
# BEFORE/AFTER is a plain `diff`. A key that changes casing (snake_case -> camelCase), a field that
# disappears, or a date that turns from string to number all show up as line changes.
#
# Two different paths on purpose:
#   * SUCCESS shapes go through Kong (:8000) — that is the real client path, and what the response
#     contract describes.
#   * The SERVICE 401 envelope is captured DIRECTLY over ecommerce-net. Kong answers an unauthenticated
#     request itself with its own shape ({"message":"Unauthorized"}), so asking Kong for a 401 measures
#     Kong, not the service. The service envelope is the 4-key one the contract pins.
#
# Usage: infra/scripts/capture-shapes.sh <output-dir>     (fleet must be up and seeded)
set -euo pipefail

OUT="${1:?usage: capture-shapes.sh <output-dir>}"
mkdir -p "$OUT"
BASE="http://localhost:8000"
REV=$(git rev-parse HEAD)

EMAIL="shape-$(date +%s)@example.com"; PW="ShapeCapture12345"
curl -sS -m 20 -X POST "$BASE/api/v1/auth/register" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PW\",\"name\":\"Shape Capture\"}" >/dev/null
T=$(curl -sS -m 20 -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PW\"}" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')

shape() { # name method path [body]
  local name=$1 method=$2 path=$3 body=${4:-}
  local args=(-sS -m 20 -X "$method" "$BASE$path" -H "Authorization: Bearer $T")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  {
    echo "# captured-at $REV :: $method $path  (shape only: key->type, values intentionally omitted)"
    curl "${args[@]}" 2>/dev/null | python3 -c '
import sys, json
def walk(v, p=""):
    if isinstance(v, dict):
        for k in sorted(v): walk(v[k], f"{p}.{k}" if p else k)
    elif isinstance(v, list):
        # shape of the FIRST element only: a list of N identical shapes adds nothing
        if v: walk(v[0], f"{p}[]")
        else: print(f"{p}[]:empty")
    else:
        t = "null" if v is None else type(v).__name__
        print(f"{p}:{t}")
try: walk(json.load(sys.stdin))
except Exception as e: print(f"UNPARSABLE: {e}")
' | sort
  } > "$OUT/$name.shape"
  printf '  %-26s %s lines\n' "$name" "$(grep -vc '^#' "$OUT/$name.shape")"
}

echo "capturing shapes -> $OUT"
shape user-profile      GET  /api/v1/users/me
shape products-list     GET  /api/v1/products
shape product-detail    GET  /api/v1/products/1
shape categories-list   GET  /api/v1/categories
shape cart-empty        GET  /api/v1/cart
curl -sS -m 20 -X POST "$BASE/api/v1/cart/items" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $T" -d '{"product_id":1,"quantity":2}' >/dev/null
shape cart-with-items   GET  /api/v1/cart
shape orders-list       GET  /api/v1/orders
ORDER=$(curl -sS -m 20 -X POST "$BASE/api/v1/orders" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $T" -H "Idempotency-Key: shape-$(date +%s)" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin).get("id",""))')
shape order-detail      GET  "/api/v1/orders/$ORDER"
# SERVICE 401 envelopes — one per service, straight to the container. Going through Kong here would
# capture Kong's own {"message":"Unauthorized"} and silently replace the 4-key contract envelope.
{
  echo "# captured-at $REV :: no-token 401 per service, DIRECT over ecommerce-net (never through Kong)"
  for e in "user:8081:GET:/api/v1/users/me" "product:8082:POST:/api/v1/products" \
           "cart:8083:GET:/api/v1/cart" "order:8084:GET:/api/v1/orders" \
           "payment:8085:GET:/api/v1/payments/00000000-0000-0000-0000-000000000000"; do
    IFS=: read -r s p m path <<< "$e"
    printf '%s ' "$s"
    docker run --rm --network ecommerce-net curlimages/curl:8.11.1 -sS -m 10 -X "$m" \
      "http://${s}-service:${p}${path}" 2>/dev/null \
      | python3 -c 'import sys,json;print(sorted((k,type(v).__name__) for k,v in json.load(sys.stdin).items()))'
  done
} > "$OUT/auth-401.shape"
printf '  %-26s %s services\n' "auth-401" "$(grep -vc '^#' "$OUT/auth-401.shape")"

echo "done: $(ls -1 "$OUT"/*.shape | wc -l) shape files"
