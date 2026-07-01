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

log "pods:"
kubectl get pods -n ecommerce -o wide

cat <<EOF

Stack is up. North-south goes through the single Kong edge on http://localhost:8000
(host :8000 -> NodePort 30080 -> Kong :8000):
  curl http://localhost:8000/api/v1/products          # -> product-service via Kong
The RabbitMQ management UI has no public route (internal only) — reach it with:
  kubectl -n ecommerce port-forward svc/rabbitmq 15672:15672
Teardown (DATA LOSS): infra/k8s/down.sh
EOF
