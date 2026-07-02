#!/usr/bin/env bash
# One-command local bring-up: k3d single-node cluster -> build images -> side-load ->
# apply the Kustomize local overlay. Wave 5b: Kong is the single north-south edge on
# http://localhost:8000 (NodePort 30080); Consul is retired (native CoreDNS + readiness
# gating). Idempotent: safe to re-run.
set -euo pipefail

CLUSTER="ecommerce"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_DIR="$(cd "$INFRA_DIR/.." && pwd)"
OVERLAY="$SCRIPT_DIR/overlays/local"
COMPOSE_FILE="$INFRA_DIR/docker-compose.yml"

# k3d/kubectl may live in ~/.local/bin (user install, no sudo).
export PATH="$HOME/.local/bin:$PATH"

APP_IMAGES=(
  ecommerce/user-service:dev
  ecommerce/product-service:dev
  ecommerce/cart-service:dev
  ecommerce/order-service:dev
  ecommerce/payment-service:dev
  ecommerce/notification-service:dev
)
THIRD_PARTY_IMAGES=(postgres:16-alpine rabbitmq:3-management-alpine kong:3.9.3)

log() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || die "missing '$1' on PATH. See infra/k8s/README.md for install."; }

# Wait until a namespaced resource EXISTS (the operator creates the Prometheus
# StatefulSet asynchronously after its CR is applied).
wait_exists() {
  local kind=$1 name=$2 tries=${3:-60}
  for _ in $(seq 1 "$tries"); do
    kubectl -n ecommerce get "$kind" "$name" >/dev/null 2>&1 && return 0
    sleep 2
  done
  return 1
}

# --- 0. tools ---------------------------------------------------------------
need docker
need k3d
need kubectl

# --- 1. inotify limits (host prep; needs sudo) ------------------------------
WANT_WATCHES=524288
WANT_INSTANCES=512
have_watches=$(cat /proc/sys/fs/inotify/max_user_watches 2>/dev/null || echo 0)
have_instances=$(cat /proc/sys/fs/inotify/max_user_instances 2>/dev/null || echo 0)
if { [ "$have_watches" -lt "$WANT_WATCHES" ] || [ "$have_instances" -lt "$WANT_INSTANCES" ]; } \
   && [ "${SKIP_INOTIFY_CHECK:-0}" != "1" ]; then
  cat >&2 <<EOF

inotify limits too low for a 13-pod cluster (the #1 cause of CrashLoops on Ubuntu).
  current : max_user_watches=$have_watches  max_user_instances=$have_instances
  required: max_user_watches=$WANT_WATCHES  max_user_instances=$WANT_INSTANCES

Run this ONCE on the host (needs sudo), then re-run this script:

  ! sudo sh -c 'printf "fs.inotify.max_user_watches=524288\nfs.inotify.max_user_instances=512\n" > /etc/sysctl.d/99-k8s-inotify.conf && sysctl --system'

(Override for experiments only: SKIP_INOTIFY_CHECK=1 $0)
EOF
  exit 1
fi

# --- 2. cluster -------------------------------------------------------------
if k3d cluster list 2>/dev/null | awk 'NR>1{print $1}' | grep -qx "$CLUSTER"; then
  log "cluster '$CLUSTER' already exists — reusing"
else
  log "creating k3d cluster '$CLUSTER' (single node, Traefik disabled, host :8000 reserved for Kong/5b)"
  k3d cluster create "$CLUSTER" \
    --servers 1 --agents 0 \
    --k3s-arg "--disable=traefik@server:*" \
    -p "8000:30080@server:0"
fi
kubectl config use-context "k3d-$CLUSTER" >/dev/null

# --- 3. build app images ----------------------------------------------------
log "building application images (ecommerce/*-service:dev)"
ENV_FOR_BUILD="$INFRA_DIR/.env"; [ -f "$ENV_FOR_BUILD" ] || ENV_FOR_BUILD="$INFRA_DIR/.env.example"
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FOR_BUILD" build

# --- 4. side-load images into the cluster (no registry in 5a) ---------------
log "importing third-party images"
for img in "${THIRD_PARTY_IMAGES[@]}"; do
  docker image inspect "$img" >/dev/null 2>&1 || docker pull "$img"
done
k3d image import "${THIRD_PARTY_IMAGES[@]}" -c "$CLUSTER"
log "importing application images"
k3d image import "${APP_IMAGES[@]}" -c "$CLUSTER"

# --- 5. secrets (gitignored; never committed) -------------------------------
if [ ! -f "$OVERLAY/secret.env" ]; then
  log "no secret.env yet — seeding from secret.env.example (REVIEW before any shared use)"
  cp "$OVERLAY/secret.env.example" "$OVERLAY/secret.env"
  echo "   edit $OVERLAY/secret.env to set real local values (placeholders boot a working dev stack)."
fi

# --- 6. apply ---------------------------------------------------------------
log "applying overlays/local"
kubectl apply -k "$OVERLAY"

# --- 7. wait for readiness --------------------------------------------------
log "waiting for databases + broker"
for sts in user-db product-db order-db cart-db payment-db notification-db; do
  kubectl -n ecommerce rollout status "statefulset/$sts" --timeout=240s
done
kubectl -n ecommerce rollout status deploy/rabbitmq --timeout=240s
log "waiting for application services"
kubectl -n ecommerce rollout status deploy/user-service deploy/product-service \
  deploy/cart-service deploy/order-service deploy/payment-service deploy/notification-service \
  --timeout=300s
log "waiting for the Kong edge"
kubectl -n ecommerce rollout status deploy/kong --timeout=180s

# --- 8. observability: metrics pillar (Wave 5c) -----------------------------
# Applied as a SEPARATE step (NOT folded into overlays/local). --server-side is
# MANDATORY: kube-prometheus-stack's Prometheus/Alertmanager CRDs exceed the 262144-byte
# client-side annotation limit, so a plain `kubectl apply` fails "metadata.annotations:
# Too long". Observability images are pulled by kubelet from public registries at
# bring-up (NOT side-loaded — `k3d image import` doubles host+node storage and disk is
# the tight constraint here); the first run needs internet for ~7 images (~1.3 GB).
log "applying observability metrics (Prometheus Operator + Prometheus + Grafana)"
# kube-prometheus-stack ships its CRDs AND CRs (Prometheus, ServiceMonitor) in ONE
# kustomization, so a single apply RACES: the CRs are rejected with "no matches for kind
# ServiceMonitor" because the just-applied CRDs aren't yet established in discovery — the
# operator + Grafana land but the Prometheus CR + ServiceMonitors silently don't. Fix:
# apply once (CRDs + workloads land; CRs may be rejected -> tolerate), WAIT for the CRDs
# to establish, then apply again so the CRs register. Both passes are idempotent; the
# second one is the real gate (no `|| true`).
kubectl apply -k "$SCRIPT_DIR/base/observability/metrics" --server-side --force-conflicts || true
kubectl wait --for=condition=established --timeout=120s \
  crd/prometheuses.monitoring.coreos.com \
  crd/servicemonitors.monitoring.coreos.com \
  crd/prometheusrules.monitoring.coreos.com
kubectl apply -k "$SCRIPT_DIR/base/observability/metrics" --server-side --force-conflicts
log "waiting for the metrics stack (operator, Prometheus, Grafana)"
kubectl -n ecommerce rollout status deploy/kps-operator --timeout=300s
wait_exists statefulset prometheus-kps-prometheus 90 \
  || die "Prometheus Operator did not create statefulset/prometheus-kps-prometheus"
kubectl -n ecommerce rollout status statefulset/prometheus-kps-prometheus --timeout=420s
kubectl -n ecommerce rollout status deploy/kube-prometheus-stack-grafana --timeout=420s

# --- 9. observability: logs pillar (Wave 5c; gated, independently skippable) -
# Loki + Alloy stand apart from the metrics demo. Skip with SKIP_LOGS=1, or auto-skip if
# the node is tight (best-effort `kubectl top nodes`; metrics-server may still be warming
# up, in which case we proceed).
if [ "${SKIP_LOGS:-0}" = "1" ]; then
  log "SKIP_LOGS=1 -> skipping the logs pillar (Loki + Alloy)"
else
  logs_ok=1
  if mempct=$(kubectl top nodes --no-headers 2>/dev/null | awk 'NR==1{gsub("%","",$5); print $5}'); then
    # metrics-server may report "<unknown>" while warming up — treat any non-numeric
    # value as 0 so the gate fails open SILENTLY and intentionally (no "integer
    # expression expected" noise from the -ge test).
    case "$mempct" in ''|*[!0-9]*) mempct=0;; esac
    if [ "$mempct" -ge 85 ]; then
      logs_ok=0
      log "node memory at ${mempct}% -> SKIPPING logs to protect the metrics demo. Free headroom and apply manually: kubectl apply -k infra/k8s/base/observability/logs --server-side"
    fi
  fi
  if [ "$logs_ok" = "1" ]; then
    log "applying observability logs (monolithic Loki + Alloy DaemonSet)"
    kubectl apply -k "$SCRIPT_DIR/base/observability/logs" --server-side
    kubectl -n ecommerce rollout status statefulset/loki --timeout=420s
    kubectl -n ecommerce rollout status daemonset/alloy --timeout=300s
  fi
fi

log "pods:"
kubectl get pods -n ecommerce -o wide

cat <<EOF

Stack is up. North-south goes through the single Kong edge on http://localhost:8000
(host :8000 -> NodePort 30080 -> Kong :8000):
  curl http://localhost:8000/api/v1/products          # -> product-service via Kong
The RabbitMQ management UI has no public route (internal only) — reach it with:
  kubectl -n ecommerce port-forward svc/rabbitmq 15672:15672

Observability (Wave 5c) is ClusterIP-only — NO Kong route, NO NodePort. Port-forward:
  kubectl -n ecommerce port-forward svc/kube-prometheus-stack-grafana 3000:80   # Grafana -> http://localhost:3000
  kubectl -n ecommerce port-forward svc/kps-prometheus 9090:9090                # Prometheus -> http://localhost:9090
Grafana admin: user/password from the grafana-admin Secret (secret.env GRAFANA_ADMIN_*).
  Prometheus -> Status -> Targets: 5 Spring /actuator/prometheus + notification /metrics +
  rabbitmq :15692 + kong :8100 (the service targets stay DOWN until the instrumented
  service images are built). Grafana dashboards: Spring HTTP/JVM, FastAPI, RabbitMQ, Kong.
Teardown (DATA LOSS): infra/k8s/down.sh
EOF
