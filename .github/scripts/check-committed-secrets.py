#!/usr/bin/env python3
"""Fail if any tracked YAML under infra/ contains a populated Kubernetes Secret.

base64 is not encryption: a committed `kind: Secret` with data/stringData hands its
values to anyone with repo read access, and base64 blobs typically evade gitleaks.
This parses YAML documents (never a text grep), so the literal string "kind: Secret"
inside CRD schema descriptions (e.g. vendored rendered.yaml) cannot false-positive,
and a real Secret document cannot hide in formatting. Unparseable YAML fails closed.
"""

from __future__ import annotations

import subprocess
import sys
from typing import Any, Iterator

import yaml


class GuardLoader(yaml.SafeLoader):
    """SafeLoader that also accepts a bare `=` scalar.

    PyYAML resolves a plain `=` to the YAML 1.1 tag:yaml.org,2002:value tag, which
    SafeLoader cannot construct (the vendored kube-prometheus-stack rendered.yaml
    contains one). Treat it as the literal string it is.
    """


GuardLoader.add_constructor(
    "tag:yaml.org,2002:value", lambda loader, node: loader.construct_yaml_str(node)
)


def tracked_yaml_files() -> list[str]:
    out = subprocess.run(
        ["git", "ls-files", "-z", "--", "infra"],
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    return [p for p in out.split("\0") if p.endswith((".yaml", ".yml"))]


def k8s_objects(node: Any) -> Iterator[dict]:
    """Yield the object and, for kind: List / *List wrappers, its items."""
    if not isinstance(node, dict):
        return
    yield node
    kind = node.get("kind")
    if isinstance(kind, str) and kind.endswith("List"):
        for item in node.get("items") or []:
            yield from k8s_objects(item)


def is_populated_secret(obj: dict) -> bool:
    if obj.get("kind") != "Secret":
        return False
    return any(
        isinstance(obj.get(field), dict) and obj[field] for field in ("data", "stringData")
    )


def main() -> int:
    files = tracked_yaml_files()
    violations: list[str] = []
    errors: list[str] = []
    for path in files:
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
        try:
            for index, doc in enumerate(yaml.load_all(text, Loader=GuardLoader)):
                for obj in k8s_objects(doc):
                    if is_populated_secret(obj):
                        name = (obj.get("metadata") or {}).get("name", "<unnamed>")
                        violations.append(f"{path} (document {index}, Secret '{name}')")
        except yaml.YAMLError as exc:
            # Fail closed: a file the guard cannot parse is a file it cannot clear.
            errors.append(f"{path}: YAML parse error: {exc}")

    for line in errors:
        print(f"::error::{line}")
    for line in violations:
        print(f"::error::populated kind: Secret committed: {line}")
    if violations or errors:
        print(
            "\nCommitted-Secret guard FAILED. Never commit a populated Secret "
            "(base64 != encryption). Use the gitignored secret.env + secretGenerator "
            "(see infra/k8s/README.md).",
            file=sys.stderr,
        )
        return 1
    print(f"Committed-Secret guard passed: {len(files)} tracked YAML files, no populated Secret.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
