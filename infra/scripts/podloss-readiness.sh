#!/usr/bin/env bash
# Pod-loss readiness baseline for the Boot 4.x pre-flight (contract invariants F2, F3, F4).
#
# WHY THIS EXISTS. Readiness reflecting a dead DB is not a Spring property — it is pgjdbc
# socketTimeout bounding a read on a socket whose peer IP has vanished. `docker pause` does NOT
# prove it: the socket stays open, the validation query blocks, and `db` goes DOWN even without
# the fix. Only pod-loss (the IP disappears, no RST) discriminates, and Boot 4.1 brings a new
# Hibernate/Hikari/pgjdbc set — so the BEFORE capture has to be a script, not a procedure someone
# retypes.
#
# THREE THINGS THIS GETS RIGHT, EACH OF WHICH IS EASY TO GET WRONG:
#
#   1. It port-forwards to the POD, never to the Service. A failing readiness probe removes the
#      pod from the Service's endpoints, so a Service-targeted forward stops answering exactly
#      when the signal appears — it would measure kube-proxy, not the application.
#
#   2. It restores the shipped configuration on exit. Component detail needs
#      MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=always, which is set NOWHERE in the fleet:
#      production runs the default `never`. A run that leaves the variable set has permanently
#      changed what an unauthenticated caller in namespace `ecommerce` can read (DB product and
#      version). The trap therefore unsets it AND re-asserts the shipped bodies (F4).
#
#   3. It asserts across the WHOLE window, not on one sample. `db` flapping DOWN for one poll and
#      back is indistinguishable from a held signal if you sample once.
#
# MEASURED, NOT ASSUMED (3.5.16, k3d, 2026-08-25) — read this before believing a diff:
#   * The SHIPPED bare `/actuator/health` body is 49 bytes:
#         {"status":"UP","groups":["liveness","readiness"]}
#     NOT the 15-byte {"status":"UP"}. `management.endpoint.health.probes.enabled: true` declares
#     the two groups and Boot serialises `groups` in the root aggregate. Only the readiness and
#     liveness GROUP bodies are the 15-byte form. So the root endpoint is asserted here on status
#     code + `.status`, and its exact body is RECORDED for the diff rather than byte-pinned to a
#     literal that this fleet never returns.
#   * Sample cadence cannot be a flat 5 s. Each health call blocks on Hikari `connection-timeout`
#     (8 s) while the DB IP is gone, so a sample costs ~6-8 s even with the three probes issued
#     concurrently. Samples are therefore SCHEDULED on 5 s boundaries and slip when a probe
#     overruns; every sample records its true elapsed time. The window still runs the full 100 s.
#
# Usage:
#   infra/scripts/podloss-readiness.sh [--out DIR] [--services "user product cart order payment"]
#                                      [--window 100] [--interval 5] [--namespace ecommerce]
#
# Requires a running k3d cluster (infra/k8s/up.sh). Runs every service even if one fails — one bad
# service must not cost the other four their captures — and exits non-zero if any row FAILED.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
INVOCATION="$0 $*"

OUT="$REPO_DIR/agent_docs/baselines/boot4"
SERVICES="user product cart order payment"
WINDOW=100          # seconds of pod-loss polling; the contract's floor is 60
INTERVAL=5          # scheduled seconds between samples
NS=ecommerce
SETTLE=15           # samples at or before this many seconds are excluded from the DOWN assertions
RECOVER_MAX=30      # Kong needs 3 successes at 5 s, so slower than this is a real finding
HTTP_TIMEOUT=12     # must exceed Hikari connection-timeout (8 s) or a probe times out client-side

while [ "$#" -gt 0 ]; do
  case "$1" in
    --out)       OUT=$2;      shift 2 ;;
    --services)  SERVICES=$2; shift 2 ;;
    --window)    WINDOW=$2;   shift 2 ;;
    --interval)  INTERVAL=$2; shift 2 ;;
    --namespace) NS=$2;       shift 2 ;;
    -h|--help)   sed -n '2,45p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

export PATH="$HOME/.local/bin:$PATH"   # k3d/kubectl are a user install on this VM
command -v kubectl >/dev/null || { echo "FATAL: kubectl not on PATH" >&2; exit 2; }
command -v jq      >/dev/null || { echo "FATAL: jq not on PATH" >&2; exit 2; }
kubectl get ns "$NS" >/dev/null 2>&1 \
  || { echo "FATAL: namespace '$NS' not reachable — is the k3d cluster up?" >&2; exit 2; }

mkdir -p "$OUT"
REV=$(git -C "$REPO_DIR" rev-parse HEAD)
WORK="$(mktemp -d)"
SHIPPED="$OUT/health-groups-shipped.txt"

MUTATED=""   # services whose Deployment carries the injected env var
SCALED=""    # StatefulSets currently scaled to 0
PF_PID=""    # live `kubectl port-forward` child
LPORT=""     # local port it chose
FAILURES=0

log()  { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
pass() { printf '  [PASS] %s\n' "$*"; }
fail() { printf '  [FAIL] %s\n' "$*" >&2; FAILURES=$((FAILURES + 1)); }

# --- primitives ---------------------------------------------------------------------------------

# Echo the HTTP status, body into $2. Never chains `|| echo`: curl already writes 000 through -w
# on a transport failure, and the chained form concatenates into a nonsense code like "000000".
http() { # url bodyfile
  local code
  code=$(curl -sS -m "$HTTP_TIMEOUT" -o "$2" -w '%{http_code}' "$1" 2>/dev/null)
  [ -n "$code" ] || code=000
  printf '%s' "$code"
}

# The READY pod of the CURRENT ReplicaSet, and only that one. Two traps live here, both hit
# during development: `items[0]` can name a pod that is already terminating, and a terminating pod
# keeps reporting phase=Running with Ready=true for several seconds after `rollout status` says
# the rollout completed. Selecting by name order then picks whichever hash sorts last — which was
# the OLD pod, so the port-forward failed with NotFound. Filter on deletionTimestamp and take the
# NEWEST by creationTimestamp.
ready_pod() { # svc
  kubectl -n "$NS" get pod -l "app.kubernetes.io/name=$1-service" \
    --sort-by=.metadata.creationTimestamp -o json 2>/dev/null \
  | jq -r '.items[]
           | select(.metadata.deletionTimestamp == null)
           | select(.status.phase == "Running")
           | select(any(.status.conditions[]?; .type == "Ready" and .status == "True"))
           | .metadata.name' \
  | tail -1 | grep .
}

stop_pf() { [ -n "$PF_PID" ] && kill "$PF_PID" 2>/dev/null; PF_PID=""; LPORT=""; return 0; }

# Port-forward to the POD (never the Service) on a kernel-chosen local port. Sets PF_PID + LPORT.
# Deliberately NOT a command substitution: that runs in a subshell, so the PID would be lost and
# the forward left orphaned for the rest of the session.
start_pf() { # pod containerPort
  local pod=$1 port=$2 i
  stop_pf
  kubectl -n "$NS" port-forward "pod/$pod" ":$port" > "$WORK/pf.log" 2>&1 &
  PF_PID=$!
  for i in $(seq 1 40); do
    LPORT=$(sed -nE 's|^Forwarding from 127\.0\.0\.1:([0-9]+).*|\1|p' "$WORK/pf.log" | head -1)
    [ -n "$LPORT" ] && break
    sleep 0.5
  done
  [ -n "$LPORT" ] || { echo "  port-forward never announced a port; kubectl said:" >&2
                       sed 's/^/    /' "$WORK/pf.log" >&2; return 1; }
  for i in $(seq 1 60); do
    curl -sf -o /dev/null -m 5 "http://127.0.0.1:$LPORT/actuator/health" && return 0
    sleep 1
  done
  echo "  pod/$pod never answered /actuator/health on 127.0.0.1:$LPORT; kubectl said:" >&2
  sed 's/^/    /' "$WORK/pf.log" >&2
  return 1
}

restart_count() { kubectl -n "$NS" get pod "$1" -o jsonpath='{.status.containerStatuses[0].restartCount}' 2>/dev/null; }

# One sample: the three probes concurrently, because serially they cost ~3x the Hikari
# connection-timeout and the cadence collapses to ~21 s.
sample() { # artefact label
  local art=$1 label=$2 db p1 p2 p3
  ( http "http://127.0.0.1:$LPORT/actuator/health"           "$WORK/h.body" > "$WORK/h.code" ) & p1=$!
  ( http "http://127.0.0.1:$LPORT/actuator/health/readiness" "$WORK/r.body" > "$WORK/r.code" ) & p2=$!
  ( http "http://127.0.0.1:$LPORT/actuator/health/liveness"  "$WORK/l.body" > "$WORK/l.code" ) & p3=$!
  wait "$p1" "$p2" "$p3"
  db=$(jq -r '.components.db.status // "ABSENT"' < "$WORK/r.body" 2>/dev/null)
  [ -n "$db" ] || db=UNPARSABLE
  {
    printf '%s health=%s readiness=%s liveness=%s db=%s\n' \
      "$label" "$(cat "$WORK/h.code")" "$(cat "$WORK/r.code")" "$(cat "$WORK/l.code")" "$db"
    printf '    health    %s\n' "$(cat "$WORK/h.body")"
    printf '    readiness %s\n' "$(cat "$WORK/r.body")"
    printf '    liveness  %s\n' "$(cat "$WORK/l.body")"
  } >> "$art"
}

# --- F4: the shipped bodies ----------------------------------------------------------------------

# Bare readiness and liveness are 200 and byte-equal to {"status":"UP"} with no
# components/details/groups. This is F4, and it is also the proof that the run left production
# disclosure exactly as it found it.
assert_shipped() { # svc
  local svc=$1 pod port body code n p
  pod=$(ready_pod "$svc") || { fail "$svc: no ready pod for the shipped-body re-assertion"; return 1; }
  port=$(kubectl -n "$NS" get pod "$pod" -o jsonpath='{.spec.containers[0].ports[0].containerPort}')
  start_pf "$pod" "$port" || { fail "$svc: could not port-forward for the shipped check"; return 1; }

  for p in readiness liveness; do
    code=$(http "http://127.0.0.1:$LPORT/actuator/health/$p" "$WORK/shipped.body")
    body=$(cat "$WORK/shipped.body")
    n=$(wc -c < "$WORK/shipped.body")
    printf '%-8s /actuator/health/%-9s %s %4s %s\n' "$svc" "$p" "$code" "$n" "$body" >> "$SHIPPED"
    if [ "$code" = "200" ] && [ "$body" = '{"status":"UP"}' ]; then
      pass "$svc shipped /actuator/health/$p -> 200, byte-equal {\"status\":\"UP\"} ($n bytes)"
    else
      fail "$svc shipped /actuator/health/$p -> $code $body (want 200 and exactly {\"status\":\"UP\"})"
    fi
    if grep -qE '"(components|details|groups)"' "$WORK/shipped.body"; then
      fail "$svc shipped /actuator/health/$p discloses components/details/groups"
    fi
  done

  # The root aggregate is RECORDED, not byte-pinned: on this fleet it legitimately carries
  # `groups` (see the header). Its exact bytes are the evidence a Boot 4 diff needs.
  code=$(http "http://127.0.0.1:$LPORT/actuator/health" "$WORK/shipped.body")
  printf '%-8s /actuator/health%-10s %s %4s %s\n' "$svc" "" "$code" \
    "$(wc -c < "$WORK/shipped.body")" "$(cat "$WORK/shipped.body")" >> "$SHIPPED"
  if [ "$code" = "200" ] && [ "$(jq -r '.status' < "$WORK/shipped.body" 2>/dev/null)" = "UP" ]; then
    pass "$svc shipped /actuator/health -> 200 .status=UP"
  else
    fail "$svc shipped /actuator/health -> $code $(cat "$WORK/shipped.body")"
  fi
  if grep -qE '"(components|details)"' "$WORK/shipped.body"; then
    fail "$svc shipped /actuator/health discloses components/details — show-details survived"
  fi
  stop_pf
}

# --- restore -------------------------------------------------------------------------------------

cleanup() {
  local rc=$? svc sts
  trap - EXIT INT TERM
  stop_pf
  log "restoring: scaling databases back, unsetting MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS"
  for sts in $SCALED; do kubectl -n "$NS" scale "statefulset/$sts" --replicas=1 >/dev/null 2>&1; done
  for sts in $SCALED; do kubectl -n "$NS" rollout status "statefulset/$sts" --timeout=240s >/dev/null 2>&1; done
  for svc in $MUTATED; do
    kubectl -n "$NS" set env "deployment/$svc-service" MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS- >/dev/null 2>&1
  done
  for svc in $MUTATED; do
    kubectl -n "$NS" rollout status "deployment/$svc-service" --timeout=300s >/dev/null 2>&1
  done
  if [ -n "$MUTATED" ]; then
    log "F4 — re-asserting the SHIPPED bodies (no MANAGEMENT_* injection) -> $SHIPPED"
    printf '# captured-at %s :: %s   [trap: after unsetting MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS]\n' \
      "$REV" "$INVOCATION" > "$SHIPPED"
    for svc in $MUTATED; do assert_shipped "$svc"; done
    echo "  artefact: $SHIPPED  sha256=$(sha256sum "$SHIPPED" | cut -d' ' -f1)"
  fi
  rm -rf "$WORK"
  echo
  if [ "$FAILURES" -eq 0 ] && [ "$rc" -eq 0 ]; then
    echo "== podloss-readiness: ALL ASSERTED ROWS PASSED =="
    exit 0
  fi
  echo "== podloss-readiness: $FAILURES FAILED assertion row(s) =="
  exit 1
}
trap cleanup EXIT INT TERM

# --- the run -------------------------------------------------------------------------------------

run_service() { # svc
  local svc=$1 art="$OUT/podloss-$svc.txt"
  local pod port rc_before rc_after image t0 elapsed next now_el r0 code recov
  local samples down_db down_root live_ok bad_db bad_root bad_live

  log "$svc — injecting MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=always"
  kubectl -n "$NS" set env "deployment/$svc-service" MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=always >/dev/null
  MUTATED="$MUTATED $svc"
  kubectl -n "$NS" rollout status "deployment/$svc-service" --timeout=300s >/dev/null \
    || { fail "$svc: rollout after env injection did not complete"; return 1; }

  pod=$(ready_pod "$svc") || { fail "$svc: no ready pod"; return 1; }
  port=$(kubectl -n "$NS" get pod "$pod" -o jsonpath='{.spec.containers[0].ports[0].containerPort}')
  image=$(kubectl -n "$NS" get pod "$pod" -o jsonpath='{.status.containerStatuses[0].imageID}')
  start_pf "$pod" "$port" || { fail "$svc: port-forward to pod/$pod failed"; return 1; }
  rc_before=$(restart_count "$pod")

  {
    printf '# captured-at %s :: %s\n' "$REV" "$INVOCATION"
    printf '# service=%s pod=%s (POD port-forward, never the Service) containerPort=%s localPort=%s\n' \
      "$svc" "$pod" "$port" "$LPORT"
    printf '# imageID=%s\n' "$image"
    printf '# window=%ss interval=%ss(scheduled; slips when a probe blocks on Hikari connection-timeout) settle=%ss\n' \
      "$WINDOW" "$INTERVAL" "$SETTLE"
    printf '# MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=always injected for this run; unset by the trap\n'
    printf '# restartCount(before)=%s\n' "$rc_before"
    printf '#\n# <t=elapsed since scale-down> health/readiness/liveness = HTTP status; db = .components.db.status of readiness\n'
  } > "$art"

  # Two healthy samples first: without them a capture that is DOWN from the first sample cannot be
  # distinguished from a service that was never up.
  log "$svc — two healthy samples before scale-down"
  sample "$art" "pre1"
  sample "$art" "pre2"

  log "$svc — kubectl scale statefulset $svc-db --replicas=0 (pod loss: the IP vanishes, no RST)"
  kubectl -n "$NS" scale "statefulset/$svc-db" --replicas=0 >/dev/null
  SCALED="$SCALED $svc-db"
  t0=$(date +%s); next=0
  while :; do
    elapsed=$(( $(date +%s) - t0 ))
    [ "$elapsed" -ge "$WINDOW" ] && break
    sample "$art" "t=$elapsed"
    next=$(( next + INTERVAL ))
    now_el=$(( $(date +%s) - t0 ))
    [ "$next" -gt "$now_el" ] && sleep $(( next - now_el ))
  done

  log "$svc — kubectl scale statefulset $svc-db --replicas=1, timing the return to 200"
  kubectl -n "$NS" scale "statefulset/$svc-db" --replicas=1 >/dev/null
  SCALED=$(printf '%s' "$SCALED" | tr ' ' '\n' | grep -vx "$svc-db" | tr '\n' ' ')
  r0=$(date +%s); recov=-1
  while [ $(( $(date +%s) - r0 )) -lt 180 ]; do
    code=$(http "http://127.0.0.1:$LPORT/actuator/health" "$WORK/rec.body")
    if [ "$code" = "200" ]; then recov=$(( $(date +%s) - r0 )); break; fi
    sleep 2
  done
  rc_after=$(restart_count "$pod")
  { printf '#\n# recovery_seconds_after_replicas_1=%s\n' "$recov"
    printf '# restartCount(after)=%s\n' "$rc_after"; } >> "$art"

  log "$svc — assertions"
  samples=$(grep -c '^t=' "$art")
  bad_db=$(awk   -v s="$SETTLE" '/^t=/ {split($1,a,"="); if (a[2]+0 >  s && $5 != "db=DOWN")      c++} END {print c+0}' "$art")
  down_db=$(awk  -v s="$SETTLE" '/^t=/ {split($1,a,"="); if (a[2]+0 >  s && $5 == "db=DOWN")      c++} END {print c+0}' "$art")
  bad_root=$(awk -v s="$SETTLE" '/^t=/ {split($1,a,"="); if (a[2]+0 >  s && $2 != "health=503")   c++} END {print c+0}' "$art")
  down_root=$(awk -v s="$SETTLE" '/^t=/ {split($1,a,"="); if (a[2]+0 > s && $2 == "health=503")   c++} END {print c+0}' "$art")
  bad_live=$(awk  '/^t=|^pre/ {if ($4 != "liveness=200") c++} END {print c+0}' "$art")
  live_ok=$(awk   '/^t=|^pre/ {if ($4 == "liveness=200") c++} END {print c+0}' "$art")

  if [ "$down_db" -eq 0 ]; then
    fail "$svc F3: no db=DOWN sample after ${SETTLE}s — the window produced nothing to assert on"
  elif [ "$bad_db" -eq 0 ]; then
    pass "$svc F3: db DOWN on ALL $down_db samples later than ${SETTLE}s (window ${WINDOW}s)"
  else
    fail "$svc F3: db was not DOWN on $bad_db of $((down_db + bad_db)) samples after ${SETTLE}s"
  fi

  if [ "$down_root" -gt 0 ] && [ "$bad_root" -eq 0 ]; then
    pass "$svc F2: bare /actuator/health 503 on ALL $down_root samples later than ${SETTLE}s"
  else
    fail "$svc F2: bare /actuator/health not 503 on $bad_root of $((down_root + bad_root)) samples after ${SETTLE}s"
  fi

  if [ "$bad_live" -eq 0 ] && [ "$live_ok" -gt 0 ]; then
    pass "$svc F3: liveness 200 on ALL $live_ok samples, throughout"
  else
    fail "$svc F3: liveness left 200 on $bad_live sample(s)"
  fi

  if [ "$recov" -ge 0 ] && [ "$recov" -le "$RECOVER_MAX" ]; then
    pass "$svc F2: back to 200 ${recov}s after --replicas=1 (limit ${RECOVER_MAX}s)"
  else
    fail "$svc F2: recovery took ${recov}s after --replicas=1 (limit ${RECOVER_MAX}s; -1 = never)"
  fi

  if [ -n "$rc_before" ] && [ "$rc_before" = "$rc_after" ]; then
    pass "$svc F3: restartCount identical before/after ($rc_before) — liveness never tripped"
  else
    fail "$svc F3: restartCount moved: before=$rc_before after=$rc_after"
  fi

  printf '# samples=%s db_down_after_settle=%s health_503_after_settle=%s liveness_200=%s\n' \
    "$samples" "$down_db" "$down_root" "$live_ok" >> "$art"
  stop_pf
  echo "  artefact: $art  sha256=$(sha256sum "$art" | cut -d' ' -f1)"
}

echo "== podloss-readiness :: rev $REV :: namespace $NS :: out $OUT =="
echo "   services: $SERVICES"
for svc in $SERVICES; do run_service "$svc"; done
