#!/usr/bin/env bash
# AUTHORING-ONLY (Option C, Wave 5c logs pillar). Re-render the PINNED Loki + Alloy
# charts into the committed rendered.yaml. Helm is a dev-time tool only; bring-up is a
# plain `kubectl apply -k` (see up.sh). Re-run on a chart bump for a reviewable diff.
#
# Loki chart repo is grafana-community/helm-charts (the OLD grafana/loki is GEL-only).
set -euo pipefail

LOKI_CHART="grafana-community/loki"
LOKI_VERSION="18.3.1"       # re-verify: helm search repo "$LOKI_CHART" --versions | head
ALLOY_CHART="grafana/alloy"
ALLOY_VERSION="1.10.0"      # re-verify: helm search repo "$ALLOY_CHART" --versions | head
NAMESPACE="ecommerce"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$SCRIPT_DIR/rendered.yaml"

command -v helm >/dev/null 2>&1 || { echo "helm not on PATH (authoring-only install to ~/.local/bin)"; exit 1; }

helm repo add grafana-community https://grafana-community.github.io/helm-charts >/dev/null 2>&1 || true
helm repo add grafana https://grafana.github.io/helm-charts >/dev/null 2>&1 || true
helm repo update grafana-community grafana >/dev/null

{
  helm template loki "$LOKI_CHART" \
    --version "$LOKI_VERSION" -n "$NAMESPACE" \
    -f "$SCRIPT_DIR/values-loki.yaml"
  echo "---"
  helm template alloy "$ALLOY_CHART" \
    --version "$ALLOY_VERSION" -n "$NAMESPACE" \
    -f "$SCRIPT_DIR/values-alloy.yaml"
} > "$OUT"

echo "rendered loki $LOKI_VERSION + alloy $ALLOY_VERSION -> $OUT ($(wc -l < "$OUT") lines)"
