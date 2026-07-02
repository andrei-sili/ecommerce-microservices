#!/usr/bin/env bash
# AUTHORING-ONLY (Wave 5c). Re-download the 5 grafana.com dashboards OFFLINE-safe and
# normalize them for sidecar provisioning (pin datasource uids, strip __inputs). The
# CLEANED JSON is committed; there is NO runtime egress to grafana.com at bring-up.
# Run: bash regen.sh   (needs network + python3; helm not required here)
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# id -> committed filename
declare -A DASH=(
  [21308]=spring-boot-http.json      # Spring Boot HTTP (RED: rate/latency/errors)
  [22108]=spring-boot-jvm.json       # Spring Boot 3 JVM (heap/GC/threads)
  [22676]=fastapi-observability.json # FastAPI Observability (instrumentator)
  [10991]=rabbitmq-overview.json     # RabbitMQ-Overview
  [7424]=kong.json                   # Kong (official)
)

for id in "${!DASH[@]}"; do
  tmp="raw-${id}.json"
  curl -sSLf "https://grafana.com/api/dashboards/${id}/revisions/latest/download" -o "$tmp"
  python3 normalize.py "$tmp" "${DASH[$id]}"
  rm -f "$tmp"
done
echo "dashboards regenerated ($(ls -1 *.json | wc -l) JSON files)"
