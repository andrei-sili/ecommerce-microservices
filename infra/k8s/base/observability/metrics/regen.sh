#!/usr/bin/env bash
# AUTHORING-ONLY (Option C, Wave 5c). Re-render kube-prometheus-stack from the PINNED
# chart into the committed rendered.yaml. Helm is a dev-time tool here, never a runtime
# dependency: bring-up is a plain `kubectl apply -k` (see up.sh). Re-run this on a chart
# bump; the result is a reviewable git diff.
#
#   helm to ~/.local/bin (v3.21.x or v4.x) if missing; then:  bash regen.sh
#
# The big Prometheus/Alertmanager CRDs exceed the 262144-byte client-side annotation
# limit, so the apply MUST be `--server-side` (up.sh does this). --include-crds bakes
# the CRDs into rendered.yaml so the operator's own resources validate.
set -euo pipefail

CHART="prometheus-community/kube-prometheus-stack"
VERSION="87.5.1"          # re-verify: helm search repo "$CHART" --versions | head
NAMESPACE="ecommerce"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

command -v helm >/dev/null 2>&1 || { echo "helm not on PATH (authoring-only install to ~/.local/bin)"; exit 1; }

helm repo add prometheus-community https://prometheus-community.github.io/helm-charts >/dev/null 2>&1 || true
helm repo update prometheus-community >/dev/null

helm template kube-prometheus-stack "$CHART" \
  --version "$VERSION" \
  -n "$NAMESPACE" \
  -f "$SCRIPT_DIR/values-metrics.yaml" \
  --include-crds \
  > "$SCRIPT_DIR/rendered.yaml"

echo "rendered $CHART $VERSION -> $SCRIPT_DIR/rendered.yaml ($(wc -l < "$SCRIPT_DIR/rendered.yaml") lines)"
