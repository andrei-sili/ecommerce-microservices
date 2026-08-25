#!/usr/bin/env bash
# Deterministic Micrometer capture for the Boot 4.x pre-flight (contract invariant F8, feeding F7).
#
# WHY THIS EXISTS. Micrometer registers meters LAZILY: `http_server_requests_*` only after a
# request, its `status` label values only for statuses actually returned, `hikaricp_*` only after
# the pool is used, AMQP meters only after a broker connection. A single arbitrary curl therefore
# produces a traffic-dependent file — and the worst case is an EMPTY `hikaricp` baseline, which
# then satisfies its own BEFORE/AFTER diff and reports a held invariant that was never measured.
#
# So the capture is driven, not sampled: a FIXED sequence per service (one 200 on a DB-backed
# read, one 401, one 404, one /actuator/health), one scrape interval of settling, then the scrape.
#
# THE CONTROL IS THE POINT. Two captures of the SAME build 60 s apart must be byte-identical. Only
# an empty control admits the cross-version diff as evidence: without it, a non-empty 3.5.16-vs-
# 4.1.0 diff cannot be told apart from a capture that was simply noisy. A run whose control is
# non-empty FAILS here rather than shipping a baseline nobody can reason about.
#
# TARGET: COMPOSE, not k3d. Every actuator/meter row in the contract (F1, F5, F7, F9, F12) is
# captured against the service container on `ecommerce-net`, per the section-3 preamble; only F3's
# pod-loss row is K8s-specific because pod-loss is a K8s failure mode. Requests go DIRECTLY to the
# service, never through Kong: Kong validates RS256 at the edge, so an edge 401 is Kong's envelope
# and its own meters, not the service's.
#
# PRECONDITION: the fleet must be SEEDED (infra/scripts/seed-baseline.sh). Payment exposes no list
# endpoint, so its "200 on a DB-backed read" needs a real payment row; the script picks it with
#   SELECT id FROM payments WHERE status = 'SUCCEEDED' ORDER BY id::text LIMIT 1
# — arbitrary but a TOTAL order, so both captures drive the identical row. `id` is a random UUID
# primary key, so there is no "lowest id" to take, and `created_at` can tie across rows written in
# one transaction. An unseeded fleet FAILS LOUDLY here; it is never quietly downgraded.
#
# Usage:
#   infra/scripts/metrics-warmup.sh [--out DIR] [--services "user product cart order payment"]
#                                   [--settle 30] [--control-gap 60] [--network ecommerce-net]
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
INVOCATION="$0 $*"

OUT="$REPO_DIR/agent_docs/baselines/boot4"
SERVICES="user product cart order payment"
SETTLE=30        # one Prometheus scrape interval (values-metrics.yaml: scrapeInterval: 30s)
CONTROL_GAP=60   # the contract's control window
NETWORK=ecommerce-net
CURL_IMAGE=curlimages/curl:8.11.1

while [ "$#" -gt 0 ]; do
  case "$1" in
    --out)         OUT=$2;          shift 2 ;;
    --services)    SERVICES=$2;     shift 2 ;;
    --settle)      SETTLE=$2;       shift 2 ;;
    --control-gap) CONTROL_GAP=$2;  shift 2 ;;
    --network)     NETWORK=$2;      shift 2 ;;
    -h|--help)     sed -n '2,40p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

port_of() { case "$1" in user) echo 8081;; product) echo 8082;; cart) echo 8083;;
                         order) echo 8084;; payment) echo 8085;;
                         *) echo "unknown service: $1" >&2; return 1;; esac; }

mkdir -p "$OUT"
REV=$(git -C "$REPO_DIR" rev-parse HEAD)
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
FAILURES=0
CONTROL="$OUT/metrics-control.txt"

log()  { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
pass() { printf '  [PASS] %s\n' "$*"; }
fail() { printf '  [FAIL] %s\n' "$*" >&2; FAILURES=$((FAILURES + 1)); }
die()  { printf '\nFATAL: %s\n' "$*" >&2; exit 2; }

command -v docker >/dev/null || die "docker not on PATH"
docker network inspect "$NETWORK" >/dev/null 2>&1 \
  || die "network '$NETWORK' not found — bring the compose stack up first (docker compose -f infra/docker-compose.yml up -d)"

# One curl container per request, exactly the section-3 preamble's idiom, so the command recorded
# in the artefact header is the command that ran. The body comes back on STDOUT and is split off
# host-side rather than written through a bind mount: curlimages/curl runs as uid 100, which cannot
# write into a mktemp -d owned by the invoking user, and the failure would look like an empty body.
req() { # method url [extra curl args...] -> echoes status; body into $WORK/body
  local method=$1 url=$2; shift 2
  local out code
  out=$(docker run --rm --network "$NETWORK" "$CURL_IMAGE" \
          -sS -m 20 -w '\n%{http_code}' -X "$method" "$@" "$url" 2>/dev/null)
  code="${out##*$'\n'}"
  printf '%s' "${out%$'\n'*}" > "$WORK/body"
  [ -n "$code" ] || code=000
  printf '%s' "$code"
}

scrape() { # svc outfile
  docker run --rm --network "$NETWORK" "$CURL_IMAGE" \
    -sS -m 30 "http://$1-service:$(port_of "$1")/actuator/prometheus" > "$2" 2>/dev/null
}

# F7's normalisation: drop the value, blank every label VALUE, keep family + label KEYS, sort -u.
# What survives is the meter SIGNATURE — the thing the committed dashboards select on.
signature() { # rawfile -> stdout
  grep -v '^#' "$1" | sed -E 's/ [0-9.eE+-]+$//' | sed -E 's/="[^"]*"/=""/g' | sort -u
}

# --- credentials and the payment row --------------------------------------------------------------
# Read from the RUNNING containers, never hardcoded, exactly as seed-baseline.sh does.
log "resolving the ADMIN identity and the payment row to read"
ADMIN_EMAIL=$(docker exec ecommerce-user-service printenv ADMIN_EMAIL 2>/dev/null) \
  || die "cannot read ADMIN_EMAIL from ecommerce-user-service — is the compose stack up?"
ADMIN_PW=$(docker exec ecommerce-user-service printenv ADMIN_PASSWORD 2>/dev/null)
[ -n "$ADMIN_EMAIL" ] && [ -n "$ADMIN_PW" ] || die "ADMIN_EMAIL/ADMIN_PASSWORD empty in the user container"

code=$(req POST "http://user-service:8081/api/v1/auth/login" \
         -H 'Content-Type: application/json' \
         -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PW\"}")
[ "$code" = "200" ] || die "admin login returned $code: $(cat "$WORK/body")"
TOKEN=$(jq -r '.access_token' < "$WORK/body")
[ -n "$TOKEN" ] && [ "$TOKEN" != "null" ] || die "admin login gave no access_token"
echo "  admin token acquired (RS256, issued by user-service)"

PAY_DB_USER=$(docker exec ecommerce-payment-db printenv POSTGRES_USER 2>/dev/null)
[ -n "$PAY_DB_USER" ] || die "cannot read POSTGRES_USER from ecommerce-payment-db"
PAYMENT_ID=$(docker exec ecommerce-payment-db psql -U "$PAY_DB_USER" -d payment_db -tAc \
  "SELECT id FROM payments WHERE status = 'SUCCEEDED' ORDER BY id::text LIMIT 1" 2>/dev/null | tr -d '[:space:]')
[ -n "$PAYMENT_ID" ] || die "no SUCCEEDED payment in payment_db.

Payment has no list endpoint, so its '200 on a DB-backed read' row needs a real payment row and
there is nothing to select. Seed the fleet first and re-run:

    infra/scripts/seed-baseline.sh

This is deliberately fatal. Substituting a 404 for the 200 would silently change what the meter
capture exercises, which is the exact class of traffic-dependent baseline F8 exists to prevent."
echo "  payment row: $PAYMENT_ID (SUCCEEDED, lowest by id::text — a total order, so both captures drive the same row)"

# --- phase 1: the fixed sequence ------------------------------------------------------------------
# 200 on a DB-backed read, then 401, then 404, then /actuator/health. Same order every run.
declare -A GOT
drive() { # svc
  local svc=$1 p base c
  p=$(port_of "$svc"); base="http://$svc-service:$p"
  local u200 m200 u401 m401 u404 m404
  local body404=()   # cart's 404 row is the only one that needs a request body
  case "$svc" in
    user)    m200=GET  u200="$base/api/v1/users/me"
             m401=GET  u401="$base/api/v1/users/me"
             m404=GET  u404="$base/api/v1/users/does-not-exist" ;;
    product) m200=GET  u200="$base/api/v1/products"
             m401=POST u401="$base/api/v1/products"
             m404=GET  u404="$base/api/v1/products/999999999" ;;
    # NOT `DELETE /cart/items/{id}`: CartService.removeItem is deliberately idempotent — it
    # `ifPresent`s its way through a missing item and returns 204, so it can never produce the 404
    # this row needs. `PUT /cart/items/{id}` is the one that throws CART_ITEM_NOT_FOUND, and it
    # throws BEFORE the product-service call, so the row stays local to cart.
    cart)    m200=GET  u200="$base/api/v1/cart"
             m401=GET  u401="$base/api/v1/cart"
             m404=PUT  u404="$base/api/v1/cart/items/999999999"
             body404=(-H 'Content-Type: application/json' -d '{"quantity":1}') ;;
    order)   m200=GET  u200="$base/api/v1/orders"
             m401=GET  u401="$base/api/v1/orders"
             m404=GET  u404="$base/api/v1/orders/00000000-0000-0000-0000-000000000404" ;;
    payment) m200=GET  u200="$base/api/v1/payments/$PAYMENT_ID"
             m401=GET  u401="$base/api/v1/payments/00000000-0000-0000-0000-000000000000"
             m404=GET  u404="$base/api/v1/payments/00000000-0000-0000-0000-000000000404" ;;
  esac

  c=$(req "$m200" "$u200" -H "Authorization: Bearer $TOKEN")
  printf '  %-8s %-6s %-58s -> %s (want 200, DB-backed read)\n' "$svc" "$m200" "${u200#$base}" "$c"
  [ "$c" = "200" ] || fail "$svc: DB-backed read returned $c, not 200 — the capture would not exercise the read path"

  # No Authorization header at all: the contract's enumerated no-token 401 path for this service.
  c=$(req "$m401" "$u401" -H 'Content-Type: application/json' -d '{}')
  printf '  %-8s %-6s %-58s -> %s (want 401, no token)\n' "$svc" "$m401" "${u401#$base}" "$c"
  [ "$c" = "401" ] || fail "$svc: tokenless call returned $c, not 401 — no status=401 meter would register"

  c=$(req "$m404" "$u404" -H "Authorization: Bearer $TOKEN" ${body404[@]+"${body404[@]}"})
  printf '  %-8s %-6s %-58s -> %s (want 404)\n' "$svc" "$m404" "${u404#$base}" "$c"
  [ "$c" = "404" ] || fail "$svc: missing-resource call returned $c, not 404 — no status=404 meter would register"

  c=$(req GET "$base/actuator/health")
  printf '  %-8s %-6s %-58s -> %s (want 200)\n' "$svc" GET /actuator/health "$c"
  [ "$c" = "200" ] || fail "$svc: /actuator/health returned $c, not 200"

  GOT[$svc]="$m200 ${u200#$base} | $m401 ${u401#$base} | $m404 ${u404#$base} | GET /actuator/health"
}

log "phase 1 — driving the fixed sequence (200 DB-read, 401, 404, /actuator/health)"
for svc in $SERVICES; do drive "$svc"; done

log "phase 2 — settling one scrape interval (${SETTLE}s)"
sleep "$SETTLE"

log "phase 3 — capturing /actuator/prometheus"
for svc in $SERVICES; do
  scrape "$svc" "$WORK/$svc.raw" || { fail "$svc: scrape failed"; continue; }
  if ! grep -q '^# HELP' "$WORK/$svc.raw"; then
    fail "$svc: scrape has no '# HELP' — that is the no-op-registry signature, not a meter dump"
    continue
  fi
  {
    printf '# captured-at %s :: %s\n' "$REV" "$INVOCATION"
    printf '# warm-up: %s\n' "${GOT[$svc]}"
    printf '# normalisation: grep -v %s | sed -E %s | sed -E %s | sort -u\n' \
      "'^#'" "'s/ [0-9.eE+-]+\$//'" "'s/=\"[^\"]*\"/=\"\"/g'"
    signature "$WORK/$svc.raw"
  } > "$OUT/$svc-meters.txt"

  # hikaricp_* on its own, because an EMPTY hikari file satisfies its own diff and would report a
  # held invariant that was never measured (contract 6.13).
  {
    printf '# captured-at %s :: %s\n' "$REV" "$INVOCATION"
    signature "$WORK/$svc.raw" | grep '^hikaricp_'
  } > "$OUT/$svc-hikari.txt"

  local_n=$(grep -vc '^#' "$OUT/$svc-meters.txt")
  hik_n=$(grep -vc '^#' "$OUT/$svc-hikari.txt")
  if [ "$hik_n" -gt 0 ]; then
    pass "$svc: $local_n meter signatures, $hik_n hikaricp_* (non-empty)"
  else
    fail "$svc: hikaricp_* is EMPTY — a failed capture, not a pass (contract 6.13)"
  fi
done

log "phase 4 — control: same build, ${CONTROL_GAP}s later, must be byte-identical"
sleep "$CONTROL_GAP"
{
  printf '# captured-at %s :: %s\n' "$REV" "$INVOCATION"
  printf '# control: two captures of the SAME build %ss apart; each diff below must be EMPTY.\n' "$CONTROL_GAP"
  printf '# Only an empty control admits the cross-version 3.5.16-vs-4.1.0 diff as evidence.\n'
} > "$CONTROL"
for svc in $SERVICES; do
  [ -f "$WORK/$svc.raw" ] || continue
  scrape "$svc" "$WORK/$svc.raw2" || { fail "$svc: control scrape failed"; continue; }
  signature "$WORK/$svc.raw"  > "$WORK/$svc.sig1"
  signature "$WORK/$svc.raw2" > "$WORK/$svc.sig2"
  if diff -q "$WORK/$svc.sig1" "$WORK/$svc.sig2" >/dev/null; then
    pass "$svc: control diff EMPTY ($(wc -l < "$WORK/$svc.sig1") signatures, unchanged over ${CONTROL_GAP}s)"
    printf '%-8s IDENTICAL  %s signatures  sha256=%s\n' \
      "$svc" "$(wc -l < "$WORK/$svc.sig1")" "$(sha256sum "$WORK/$svc.sig1" | cut -d' ' -f1)" >> "$CONTROL"
  else
    fail "$svc: control diff NON-EMPTY — the capture is traffic-dependent, the baseline is not usable"
    { printf '%-8s DIFFERS\n' "$svc"; diff "$WORK/$svc.sig1" "$WORK/$svc.sig2" | sed 's/^/    /'; } >> "$CONTROL"
  fi
done

echo
for svc in $SERVICES; do
  [ -f "$OUT/$svc-meters.txt" ] && \
    printf '  %-24s sha256=%s\n' "$svc-meters.txt" "$(sha256sum "$OUT/$svc-meters.txt" | cut -d' ' -f1)"
  [ -f "$OUT/$svc-hikari.txt" ] && \
    printf '  %-24s sha256=%s\n' "$svc-hikari.txt" "$(sha256sum "$OUT/$svc-hikari.txt" | cut -d' ' -f1)"
done
printf '  %-24s sha256=%s\n' "metrics-control.txt" "$(sha256sum "$CONTROL" | cut -d' ' -f1)"

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "== metrics-warmup: ALL ASSERTED ROWS PASSED =="
  exit 0
fi
echo "== metrics-warmup: $FAILURES FAILED assertion row(s) =="
exit 1
