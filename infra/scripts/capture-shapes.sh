#!/usr/bin/env bash
# Capture the JSON SHAPE (key -> type, recursively) of every response the contract pins, so the
# Boot 4.x migration can be diffed structurally rather than by eyeballing bodies.
#
# Values change per run (ids, timestamps); the SHAPE must not. Each line is `path:type`, sorted, so
# BEFORE/AFTER is a plain `diff`. A key that changes casing (snake_case -> camelCase), a field that
# disappears, or a date that turns from string to number all show up as line changes.
#
# THE SHAPE FORMAT IS DELIBERATELY NOT THE CONTRACT'S TWO jq PASSES. The python walk below emits
# `:null` for a null and `[]:empty` for an empty list, which merges both passes B3 describes AND
# distinguishes null / [] / {} — the `[paths]|map(join("."))` form renders all three identically.
# Do not "simplify" it back to jq.
#
# TEE, NEVER CONSUME. Every response body is written to a file FIRST and then read twice: once to
# produce the shape, once to extract whatever the next step needs (a token, an id). The previous
# version piped the login response straight into a token extractor, so `auth-login` — one of B3's
# ten required shapes — could not be captured at all, and the register 201 went to /dev/null. A
# body that is consumed to extract a value is a body that was never baselined.
#
# EVERY CAPTURE IS GATED ON ITS EXPECTED STATUS. A `payment-201` that silently captured a 402
# envelope would diff clean against a later 402 and prove nothing. A wrong status fails the run.
#
# Two different paths on purpose:
#   * SUCCESS shapes go through Kong (:8000) — that is the real client path, and what the response
#     contract describes.
#   * The SERVICE 401 envelope is captured DIRECTLY over ecommerce-net. Kong answers an unauthenticated
#     request itself with its own shape ({"message":"Unauthorized"}), so asking Kong for a 401 measures
#     Kong, not the service. The service envelope is the 4-key one the contract pins.
#
# SIDE EFFECTS ON THE FLEET. B3 requires `order-201`, `payment-201` and `payment-402`, which cannot
# be captured without really placing orders and really charging. This run therefore commits stock:
# one unit per successful order (two orders). It creates NO products and NO categories, so a
# concurrent catalog dump sees the same rows — only `stock_quantity` moves. The 409 order fails and
# commits nothing.
#
# Usage: infra/scripts/capture-shapes.sh <output-dir> [--baseline-rev SHA]
#        (fleet must be up and seeded — see infra/scripts/seed-baseline.sh)
set -euo pipefail

OUT="${1:?usage: capture-shapes.sh <output-dir> [--baseline-rev SHA]}"; shift
# The pinned pre-flight baseline (contract section 3 preamble: `export REV=f7e549c...`). The
# artefact must name the revision it CORRESPONDS to, which is the service code that produced the
# bodies — not whatever infra/CI commit the branch tip happens to sit on. Overridable, and the
# working revision plus any service-code drift are recorded alongside so nothing is hidden.
BASELINE_REV="${REV:-f7e549c5a094b3f7f6465cfb76f2dc04f07aeb74}"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --baseline-rev) BASELINE_REV=$2; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

mkdir -p "$OUT"
BASE="http://localhost:8000"
WORKING_REV=$(git rev-parse HEAD)
DRIFT=$(git diff --name-only "$BASELINE_REV..$WORKING_REV" -- services/ | tr '\n' ' ')
[ -n "$DRIFT" ] || DRIFT="none"
STAMP="$(date +%s)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
FAILURES=0
T=""   # bearer token; empty until auth-login, so register/login go out unauthenticated

fail() { printf '  [FAIL] %s\n' "$*" >&2; FAILURES=$((FAILURES + 1)); }

py_shape='
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
'

# capture NAME WANT-STATUS METHOD PATH [JSON-BODY] [EXTRA-HEADER ...]
# Writes $OUT/NAME.shape and leaves the raw body in $WORK/body for the caller to read.
capture() {
  local name=$1 want=$2 method=$3 path=$4 body=""
  if [ "$#" -ge 5 ]; then body=$5; shift 5; else shift 4; fi
  local args=(-sS -m 20 -o "$WORK/body" -w '%{http_code}' -X "$method" "$BASE$path")
  [ -n "$T" ] && args+=(-H "Authorization: Bearer $T")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  local h; for h in "$@"; do args+=(-H "$h"); done

  local code; code=$(curl "${args[@]}" 2>/dev/null) || code=000
  {
    echo "# captured-at $BASELINE_REV :: $method $path -> $code  (shape only: key->type, values intentionally omitted)"
    echo "# working-rev $WORKING_REV :: infra/scripts/capture-shapes.sh $OUT"
    echo "# service-code drift vs baseline: $DRIFT"
    python3 -c "$py_shape" < "$WORK/body" | sort
  } > "$OUT/$name.shape"

  local n; n=$(grep -vc '^#' "$OUT/$name.shape" || true)
  if [ "$code" = "$want" ]; then
    printf '  %-16s %-3s %s lines\n' "$name" "$code" "$n"
  else
    fail "$name: HTTP $code, expected $want — the shape captured is of the WRONG response"
    printf '         body was: %s\n' "$(head -c 200 "$WORK/body")" >&2
  fi
}

# require NAME KEY... — B2: a shape that is missing its declared minimum is a FAILED capture, not a
# pass. `jq`'s paths(scalars) emits nothing for [] / {} / null, so a naive capture of an empty cart
# produces an empty-vs-empty diff that stays green while the body flips to camelCase.
require() {
  local name=$1; shift
  local f="$OUT/$name.shape" k
  for k in "$@"; do
    grep -q "^${k}:" "$f" || fail "$name: missing required line '${k}:...' — capture is not decisive"
  done
}

jsonget() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)" < "$WORK/body"; }

echo "capturing shapes -> $OUT"
echo "  baseline-rev $BASELINE_REV / working-rev $WORKING_REV / service drift: $DRIFT"

# --- auth: both bodies baselined, THEN the token is taken from the saved copy -------------------
EMAIL="shape-$STAMP@example.com"; PW="ShapeCapture12345"
capture auth-register 201 POST /api/v1/auth/register \
  "{\"email\":\"$EMAIL\",\"password\":\"$PW\",\"name\":\"Shape Capture\"}"
capture auth-login    200 POST /api/v1/auth/login \
  "{\"email\":\"$EMAIL\",\"password\":\"$PW\"}"
require auth-login access_token refresh_token token_type expires_in
T=$(jsonget "['access_token']")
[ -n "$T" ] || { echo "FATAL: no access_token in the login body" >&2; exit 1; }

capture users-me 200 GET /api/v1/users/me

# --- catalog ------------------------------------------------------------------------------------
capture product-list 200 GET "/api/v1/products?page=0&size=20"
require product-list 'content\[\].stock_quantity' 'content\[\].created_at' total_elements total_pages
cp "$WORK/body" "$WORK/products.json"   # every later capture overwrites $WORK/body
PID=$(python3 -c "import sys,json;print(json.load(open('$WORK/products.json'))['content'][0]['id'])")
# Lowest-stock ACTIVE product, for the 409. Read from live data rather than hardcoded: the cart
# caps an item at 100 units, so the 409 is only reachable through a product with stock < 100.
read -r LOW_ID LOW_STOCK <<<"$(python3 - "$WORK/products.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
active = [p for p in d["content"] if p.get("available", True)]
p = min(active, key=lambda x: x["stock_quantity"])
print(p["id"], p["stock_quantity"])
PY
)"
echo "  cart product=$PID   409 product=$LOW_ID (stock $LOW_STOCK)"

capture product-detail   200 GET "/api/v1/products/$PID"
capture product-404      404 GET /api/v1/products/999999999
require product-404 error message timestamp path
capture categories-list  200 GET /api/v1/categories

# --- cart ---------------------------------------------------------------------------------------
capture cart-empty 200 GET /api/v1/cart
curl -sS -m 20 -o /dev/null -X POST "$BASE/api/v1/cart/items" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $T" -d "{\"product_id\":$PID,\"quantity\":2}"
capture cart 200 GET /api/v1/cart
require cart 'items\[\].product_id' 'items\[\].unit_price' 'items\[\].line_total' \
             'items\[\].snapshot_at' currency total_items updated_at

# --- order + payment: the money shapes ----------------------------------------------------------
capture orders-list 200 GET /api/v1/orders
capture order-201 201 POST /api/v1/orders "" "Idempotency-Key: shapes-order-$STAMP"
ORDER_ID=$(jsonget "['id']")
capture order-detail 200 GET "/api/v1/orders/$ORDER_ID"

capture payment-201 201 POST /api/v1/payments \
  "{\"order_id\":\"$ORDER_ID\",\"payment_method_token\":\"tok_shapes_ok_$STAMP\"}" \
  "Idempotency-Key: shapes-pay-ok-$STAMP"

# A second order, because a SUCCEEDED payment already exists on the first one and the partial
# unique index enforces at most one successful charge per order.
curl -sS -m 20 -o /dev/null -X POST "$BASE/api/v1/cart/items" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $T" -d "{\"product_id\":$PID,\"quantity\":1}"
curl -sS -m 20 -o "$WORK/body" -X POST "$BASE/api/v1/orders" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $T" -H "Idempotency-Key: shapes-order2-$STAMP"
ORDER2=$(jsonget "['id']")
# SandboxGatewayAdapter declines any token containing "decline" (CARD_DECLINED).
capture payment-402 402 POST /api/v1/payments \
  "{\"order_id\":\"$ORDER2\",\"payment_method_token\":\"tok_shapes_decline_$STAMP\"}" \
  "Idempotency-Key: shapes-pay-402-$STAMP"
require payment-402 error message timestamp path payment_id failure_reason

# --- product 409 through the real client flow ---------------------------------------------------
# The reservation endpoint is an EAST-WEST call (order -> product) behind the Kong JWT edge, so
# poking it directly yields Kong's 401, not the service's 409. Drive it as a client does: more units
# in the cart than exist in stock, then place the order. This order FAILS, so it commits no stock.
if [ "$LOW_STOCK" -ge 100 ]; then
  fail "product-409: lowest stock is $LOW_STOCK and the cart caps an item at 100 — cannot exceed stock"
else
  curl -sS -m 20 -o /dev/null -X DELETE "$BASE/api/v1/cart" -H "Authorization: Bearer $T"
  curl -sS -m 20 -o /dev/null -X POST "$BASE/api/v1/cart/items" -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $T" -d "{\"product_id\":$LOW_ID,\"quantity\":$((LOW_STOCK + 1))}"
  capture product-409 409 POST /api/v1/orders "" "Idempotency-Key: shapes-409-$STAMP"
  require product-409 error message timestamp path product_id
fi

# --- SERVICE 401 envelopes — direct to the container, never through Kong ------------------------
{
  echo "# captured-at $BASELINE_REV :: no-token 401 per service, DIRECT over ecommerce-net (never through Kong)"
  echo "# working-rev $WORKING_REV :: infra/scripts/capture-shapes.sh $OUT"
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
printf '  %-16s %s services\n' "auth-401" "$(grep -vc '^#' "$OUT/auth-401.shape" || true)"

# --- the ten B3 shapes must all exist and be non-trivial -----------------------------------------
echo
MISSING=""
for s in auth-register auth-login users-me product-list product-404 cart order-201 payment-201 payment-402 product-409; do
  if [ ! -s "$OUT/$s.shape" ] || [ "$(grep -vc '^#' "$OUT/$s.shape" || true)" -eq 0 ]; then
    MISSING="$MISSING $s"
  fi
done
[ -z "$MISSING" ] || fail "B3 requires ten shapes; these are missing or empty:$MISSING"

echo "done: $(ls -1 "$OUT"/*.shape | wc -l) shape files"
if [ "$FAILURES" -eq 0 ]; then
  echo "== capture-shapes: ALL TEN B3 SHAPES CAPTURED, every status and minimum asserted =="
  exit 0
fi
echo "== capture-shapes: $FAILURES FAILED row(s) =="
exit 1
