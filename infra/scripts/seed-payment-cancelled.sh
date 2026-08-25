#!/usr/bin/env bash
# Produce ONE PaymentCancelled outbox event, reproducibly, for the Boot 4.x baseline capture.
#
# WHY THIS SCRIPT EXISTS (gate-approved exception to AC-0.4, limited NOMINALLY to PaymentCancelled):
# PaymentCancelled is NOT reachable end-to-end on this build. Verified in code and live:
#   * transitionToCancelled (PaymentPersistenceService:246) requires status == PENDING; anything else
#     logs a warning and returns. A correctly-signed payment_canceled webhook against a SUCCEEDED
#     payment returns HTTP 200 with zero effect.
#   * the webhook resolves the payment ONLY by gateway_payment_id (findPaymentByGatewayId); an
#     unresolvable id is acked without processing.
#   * gateway_payment_id is assigned ONLY on gateway approval, i.e. on the way to SUCCEEDED. A charge
#     that fails at the gateway (token containing "error" -> HTTP 502) does leave the row PENDING,
#     but with gateway_payment_id NULL.
# So "PENDING *and* gateway_payment_id set" is a state production never reaches. The event payload
# still must be baselined, because the Jackson 3 port changes how it is serialized.
#
# This script sets that one field explicitly, then drives the REAL webhook path: real signature
# verification, real PaymentPersistenceService, real OutboxService, real mapper. The payload it
# produces is genuine serializer output — only the state it starts from is seeded.
#
# The BEFORE (3.5.16) and AFTER (4.1.0) captures MUST both run THIS script, so the diff compares two
# versions rather than two procedures.
#
# Usage: infra/scripts/seed-payment-cancelled.sh     (compose fleet must be up and seeded)
set -euo pipefail

STAMP="$(date +%s)"
BASE_URL="${BASE_URL:-http://localhost:8000}"
EMAIL="cancel-${STAMP}@example.com"
PW="SeedBaseline12345"

say() { printf '\n== %s\n' "$*"; }

say "fresh user + order (avoids the single-active-payment-per-order rule)"
curl -sS -m 20 -X POST "$BASE_URL/api/v1/auth/register" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PW\",\"name\":\"Cancel Seed\"}" >/dev/null
T=$(curl -sS -m 20 -X POST "$BASE_URL/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PW\"}" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')

PRODUCT_ID="${PRODUCT_ID:-3}"
curl -sS -m 20 -X POST "$BASE_URL/api/v1/cart/items" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $T" -d "{\"product_id\":$PRODUCT_ID,\"quantity\":1}" >/dev/null
ORDER_ID=$(curl -sS -m 20 -X POST "$BASE_URL/api/v1/orders" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $T" -H "Idempotency-Key: cancel-order-$STAMP" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
echo "   order: $ORDER_ID"

say "charge with a gateway-error token -> HTTP 502, row stays PENDING (gateway_payment_id NULL)"
curl -sS -m 20 -o /dev/null -w '   charge HTTP %{http_code}\n' -X POST "$BASE_URL/api/v1/payments" \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $T" \
  -H "Idempotency-Key: cancel-pay-$STAMP" \
  -d "{\"order_id\":\"$ORDER_ID\",\"payment_method_token\":\"tok_error_$STAMP\"}" || true

PID=$(docker exec ecommerce-payment-db psql -U payment_service -d payment_db -tAc \
  "select id from payments where order_id='$ORDER_ID' and status='PENDING'" | tr -d ' ')
[ -n "$PID" ] || { echo "FATAL: no PENDING payment for $ORDER_ID"; exit 1; }

say "SEEDED STEP (the one unreachable bit): assign a gateway id to the PENDING row"
GW="gw_pending_$STAMP"
docker exec ecommerce-payment-db psql -U payment_service -d payment_db -c \
  "update payments set gateway_payment_id='$GW' where id='$PID'" >/dev/null
echo "   payment $PID -> gateway_payment_id=$GW"

say "REAL path from here: correctly-signed payment_canceled webhook, direct to the service"
# Kong blocks /payments/webhook by design (request-termination 404), so this goes over ecommerce-net.
# The secret env var is PAYMENT_WEBHOOK_SECRET (NOT WEBHOOK_SECRET — an empty value silently yields a
# wrong HMAC and a 401 that looks like a signature bug).
SECRET=$(docker exec ecommerce-payment-service printenv PAYMENT_WEBHOOK_SECRET)
[ -n "$SECRET" ] || { echo "FATAL: PAYMENT_WEBHOOK_SECRET empty"; exit 1; }
BODY=$(printf '{"event_id":"evt-cancel-%s","event_type":"payment_canceled","gateway_payment_id":"%s"}' "$STAMP" "$GW")
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -binary | xxd -p -c256)
docker run --rm --network ecommerce-net -e B="$BODY" -e S="$SIG" curlimages/curl:8.11.1 \
  sh -c 'curl -sS -m 15 -o /dev/null -w "   webhook HTTP %{http_code}\n" -X POST \
    http://payment-service:8085/api/v1/payments/webhook \
    -H "Content-Type: application/json" -H "X-Webhook-Signature: $S" -d "$B"'
sleep 2

say "result"
echo -n "   status: "
docker exec ecommerce-payment-db psql -U payment_service -d payment_db -tAc \
  "select status from payments where id='$PID'"
docker exec ecommerce-payment-db psql -U payment_service -d payment_db -tAc \
  "select '   event : '||event_type||' :: '||payload::text from outbox_events
   where event_type='PaymentCancelled' order by id desc limit 1"
