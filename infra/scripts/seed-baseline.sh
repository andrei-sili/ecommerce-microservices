#!/usr/bin/env bash
# Seed the compose fleet with the exact scenario set the Boot 4.x pre-flight baseline needs
# (task doc S0, AC-0.3), so every capture that follows is non-trivial and reproducible.
#
# Drives everything through the Kong edge (:8000) — the same path a client takes — so the captured
# shapes are the ones the contract actually pins. Idempotent by construction: every run uses a fresh
# email and fresh SKUs, so re-running adds a new scenario set rather than colliding with the old one.
#
# Scenario set produced:
#   - 1 registered user (USER role) + the bootstrapped ADMIN
#   - 2 categories; 5 active products + 1 soft-deleted, with `zzq` in exactly 2 product names
#   - 2 units of one product in the user's cart
#   - 1 placed order
#   - 1 charged payment (COMPLETED) and 1 declined payment (FAILED)
#   - 1 stock rejection (409) from an over-quantity reservation
#
# Usage: infra/scripts/seed-baseline.sh [--base-url URL]
set -euo pipefail

BASE_URL="http://localhost:8000"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --base-url) BASE_URL=$2; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

STAMP="$(date +%s)"
EMAIL="seed-${STAMP}@example.com"
PW="SeedBaseline12345"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

j() { python3 -c "import sys,json;d=json.load(sys.stdin);print(eval('d'+sys.argv[1]))" "$1"; }
api() { # METHOD PATH [token] [body]
  local m=$1 p=$2 t=${3:-} b=${4:-}
  local args=(-sS -m 20 -X "$m" "$BASE_URL$p" -H 'Content-Type: application/json')
  [ -n "$t" ] && args+=(-H "Authorization: Bearer $t")
  [ -n "$b" ] && args+=(-d "$b")
  curl "${args[@]}"
}
step() { printf '\n== %s\n' "$*"; }

# ADMIN credentials come from the running user-service, never hardcoded here.
ADMIN_EMAIL=$(docker exec ecommerce-user-service printenv ADMIN_EMAIL)
ADMIN_PW=$(docker exec ecommerce-user-service printenv ADMIN_PASSWORD)

step "admin login"
ADMIN_T=$(api POST /api/v1/auth/login '' "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PW\"}" | j "['access_token']")
[ -n "$ADMIN_T" ] || { echo "FATAL: no admin token"; exit 1; }
echo "   admin token acquired"

step "register + login the scenario user ($EMAIL)"
api POST /api/v1/auth/register '' "{\"email\":\"$EMAIL\",\"password\":\"$PW\",\"name\":\"Seed Baseline\"}" >/dev/null
USER_T=$(api POST /api/v1/auth/login '' "{\"email\":\"$EMAIL\",\"password\":\"$PW\"}" | j "['access_token']")
[ -n "$USER_T" ] || { echo "FATAL: no user token"; exit 1; }
echo "   user token acquired"

step "2 categories"
CAT_A=$(api POST /api/v1/categories "$ADMIN_T" "{\"name\":\"Seed Alpha $STAMP\",\"slug\":\"seed-alpha-$STAMP\"}" | j "['id']")
CAT_B=$(api POST /api/v1/categories "$ADMIN_T" "{\"name\":\"Seed Beta $STAMP\",\"slug\":\"seed-beta-$STAMP\"}"  | j "['id']")
echo "   categories: $CAT_A, $CAT_B"

# 5 active + 1 to soft-delete. Exactly TWO names carry the `zzq` marker (used by filter captures).
mkproduct() { # sku name price cat stock
  api POST /api/v1/products "$ADMIN_T" \
    "{\"sku\":\"$1\",\"name\":\"$2\",\"description\":\"seeded for boot4 baseline\",\"price\":$3,\"currency\":\"EUR\",\"category_id\":$4,\"stock_quantity\":$5}" \
    | j "['id']"
}
step "6 products (5 active + 1 soft-deleted; exactly 2 names contain zzq)"
P1=$(mkproduct "SEED-$STAMP-1" "Seed Widget zzq One"  "19.99" "$CAT_A" 50)
P2=$(mkproduct "SEED-$STAMP-2" "Seed Widget zzq Two"  "29.50" "$CAT_A" 40)
P3=$(mkproduct "SEED-$STAMP-3" "Seed Gadget Three"    "9.95"  "$CAT_A" 30)
P4=$(mkproduct "SEED-$STAMP-4" "Seed Gadget Four"     "149.00" "$CAT_B" 12)
P5=$(mkproduct "SEED-$STAMP-5" "Seed Gadget Five"     "5.00"  "$CAT_B" 7)
P6=$(mkproduct "SEED-$STAMP-6" "Seed Retired Six"     "1.00"  "$CAT_B" 3)
echo "   products: $P1 $P2 $P3 $P4 $P5 (active), $P6 (to be soft-deleted)"
api DELETE "/api/v1/products/$P6" "$ADMIN_T" >/dev/null
echo "   soft-deleted $P6"

step "cart: 2 units of $P1"
api POST /api/v1/cart/items "$USER_T" "{\"product_id\":$P1,\"quantity\":2}" >/dev/null
api GET /api/v1/cart "$USER_T" | head -c 200; echo

# POST /orders and POST /payments both REQUIRE an Idempotency-Key header (contract: idempotent
# creation on money paths). Sending one per logical action, distinct per scenario.
idem() { # METHOD PATH token idem-key [body]
  local m=$1 p=$2 t=$3 k=$4 b=${5:-}
  local args=(-sS -m 20 -X "$m" "$BASE_URL$p" -H 'Content-Type: application/json'
              -H "Authorization: Bearer $t" -H "Idempotency-Key: $k")
  [ -n "$b" ] && args+=(-d "$b")
  curl "${args[@]}"
}

step "place order from the cart"
ORDER=$(idem POST /api/v1/orders "$USER_T" "seed-order-$STAMP")
ORDER_ID=$(printf '%s' "$ORDER" | j "['id']")
[ -n "$ORDER_ID" ] || { echo "FATAL: no order id; body was: $ORDER"; exit 1; }
echo "   order: $ORDER_ID"

step "payment 1/2 — charge (expect COMPLETED)"
idem POST /api/v1/payments "$USER_T" "seed-ok-$STAMP" \
  "{\"order_id\":\"$ORDER_ID\",\"payment_method_token\":\"tok_seed_ok_$STAMP\"}" | head -c 260; echo

step "payment 2/2 — decline (expect FAILED)"
# a second order so the decline is not blocked by "single active payment per order"
api POST /api/v1/cart/items "$USER_T" "{\"product_id\":$P2,\"quantity\":1}" >/dev/null
ORDER2=$(idem POST /api/v1/orders "$USER_T" "seed-order2-$STAMP" | j "['id']")
echo "   order 2: $ORDER2"
idem POST /api/v1/payments "$USER_T" "seed-decline-$STAMP" \
  "{\"order_id\":\"$ORDER2\",\"payment_method_token\":\"tok_decline_$STAMP\"}" | head -c 260; echo

step "stock rejection (expect 409) — via the real flow, not a direct reservation call"
# The reservation endpoint is an EAST-WEST call (order -> product) and is JWT-protected at the Kong
# edge, so poking it directly from outside yields Kong's 401, not the service's 409. Drive it the way
# a client does: put more units in the cart than exist in stock, then place the order — order calls
# product, product refuses, and the 409 surfaces on the order response.
api DELETE "/api/v1/cart/items/$P1" "$USER_T" >/dev/null 2>&1 || true
api DELETE "/api/v1/cart/items/$P2" "$USER_T" >/dev/null 2>&1 || true
api POST /api/v1/cart/items "$USER_T" "{\"product_id\":$P5,\"quantity\":50}" >/dev/null   # stock is 7
curl -sS -m 20 -o "$WORK/409" -w '   HTTP %{http_code}\n' \
  -X POST "$BASE_URL/api/v1/orders" \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $USER_T" \
  -H "Idempotency-Key: seed-stock-409-$STAMP"
head -c 260 "$WORK/409" 2>/dev/null; echo

cat <<SUMMARY

== seed summary ==
user            : $EMAIL
categories      : $CAT_A $CAT_B
products active : $P1 $P2 $P3 $P4 $P5   (zzq in: $P1 $P2)
product deleted : $P6
orders          : $ORDER_ID $ORDER2
SUMMARY
