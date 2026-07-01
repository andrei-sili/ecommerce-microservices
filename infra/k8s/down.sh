#!/usr/bin/env bash
# Teardown the local k3d cluster. This DELETES the node container and with it every
# PVC (local-path data lives inside the node) — all DB/broker data is lost. Re-seed
# by re-running up.sh + the smoke. See infra/k8s/README.md.
set -euo pipefail
export PATH="$HOME/.local/bin:$PATH"
CLUSTER="ecommerce"
command -v k3d >/dev/null 2>&1 || { echo "k3d not on PATH"; exit 1; }
echo "Deleting k3d cluster '$CLUSTER' (data loss expected)..."
k3d cluster delete "$CLUSTER"
echo "Done."
