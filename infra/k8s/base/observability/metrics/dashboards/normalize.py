#!/usr/bin/env python3
"""Normalize grafana.com dashboard exports for offline sidecar provisioning.

grafana.com exports carry `__inputs` with `${DS_PROMETHEUS}` datasource placeholders
that only resolve on interactive import. For provisioning we pin every Prometheus
datasource ref to the fixed uid `prometheus` (and Loki to `loki`) so panels resolve with
zero runtime egress. Run: python3 normalize.py raw-<id>.json <out>.json
"""
import json
import sys

GRAFANA_DS = {"-- grafana --", "-- mixed --", "-- dashboard --", "grafana"}


def fix_datasource(val):
    """Return a concrete datasource ref for a `datasource` field value."""
    if val is None:
        return {"type": "prometheus", "uid": "prometheus"}
    if isinstance(val, str):
        low = val.lower()
        if low in GRAFANA_DS:
            return val
        if "loki" in low:
            return {"type": "loki", "uid": "loki"}
        return {"type": "prometheus", "uid": "prometheus"}
    if isinstance(val, dict):
        dtype = (val.get("type") or "").lower()
        if dtype in ("grafana", "datasource"):
            return val
        if dtype == "loki":
            return {"type": "loki", "uid": "loki"}
        return {"type": "prometheus", "uid": "prometheus"}
    return val


def walk(node):
    if isinstance(node, dict):
        for k, v in list(node.items()):
            if k == "datasource":
                node[k] = fix_datasource(v)
            else:
                walk(v)
    elif isinstance(node, list):
        for item in node:
            walk(item)


def main():
    src, dst = sys.argv[1], sys.argv[2]
    d = json.load(open(src))
    d.pop("__inputs", None)
    d.pop("__requires", None)
    # Pin datasource template variables to the default Prometheus datasource.
    for var in d.get("templating", {}).get("list", []):
        if var.get("type") == "datasource":
            q = (var.get("query") or "").lower()
            uid = "loki" if "loki" in q else "prometheus"
            var["current"] = {"text": uid.capitalize(), "value": uid, "selected": True}
    walk(d)
    # Provisioning ignores id/uid at top level; drop id so it never clashes.
    d.pop("id", None)
    json.dump(d, open(dst, "w"), indent=2, sort_keys=False)
    print(f"{src} -> {dst}")


if __name__ == "__main__":
    main()
