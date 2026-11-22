#!/usr/bin/env python3
"""Typed monorepo consistency checks invoked by scripts/check-monorepo-consistency.sh."""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from collections.abc import Callable
from pathlib import Path
from typing import Any

import yaml

from consistency_checks_extra import (
    REPO_ROOT,
    check_container_ports,
    check_dbt_refs,
    check_k8s_compose_ports,
    parse_glossary_ports,
    verify_dockerfile_ports,
)

SCRIPTS_DIR = Path(__file__).resolve().parent


def _git_argocd_yaml_paths() -> list[str]:
    out = subprocess.check_output(
        ["git", "ls-files", "--", "argocd/*.yaml", "argocd/**/*.yaml"],
        cwd=REPO_ROOT,
        text=True,
    )
    return sorted(set(out.splitlines()))


def check_istio_policies() -> list[str]:
    result = subprocess.run(
        [sys.executable, str(SCRIPTS_DIR / "verify-istio-mesh-policies.py")],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode == 0:
        return []
    detail = result.stderr.strip() or result.stdout.strip() or f"exit code {result.returncode}"
    return [detail.splitlines()[-1]]


def check_dependabot() -> list[str]:
    dependabot_path = REPO_ROOT / ".github" / "dependabot.yml"
    with dependabot_path.open("r", encoding="utf-8") as f:
        loaded: Any = yaml.safe_load(f) or {}
    cfg: dict[str, Any] = loaded if isinstance(loaded, dict) else {}

    docker_dirs: set[str] = set()
    missing_cooldown: list[str] = []
    missing_docker_groups: list[str] = []

    updates: list[Any] = cfg.get("updates", [])
    for idx, raw_upd in enumerate(updates):
        if not isinstance(raw_upd, dict):
            continue
        eco = str(raw_upd.get("package-ecosystem", ""))
        if "cooldown" not in raw_upd:
            missing_cooldown.append(f"{eco}#{idx}")
        if eco == "docker":
            if not raw_upd.get("groups"):
                missing_docker_groups.append(f"entry#{idx}")
            for d in raw_upd.get("directories", []):
                docker_dirs.add(str(d).lstrip("/"))
            if "directory" in raw_upd:
                docker_dirs.add(str(raw_upd["directory"]).lstrip("/"))

    actual_docker_dirs = {
        str(p.parent.relative_to(REPO_ROOT))
        for p in (REPO_ROOT / "services").glob("*/Dockerfile")
    }
    uncovered = sorted(actual_docker_dirs - docker_dirs)

    errors: list[str] = []
    if missing_cooldown:
        errors.append("missing-cooldown:" + ",".join(missing_cooldown))
    if missing_docker_groups:
        errors.append("ungrouped-docker:" + ",".join(missing_docker_groups))
    if uncovered:
        errors.append("uncovered-dockerfiles:" + ",".join(uncovered))
    return errors


def check_argocd_apps() -> list[str]:
    seen: dict[str, str] = {}
    duplicates: list[str] = []
    for rel_path in _git_argocd_yaml_paths():
        with (REPO_ROOT / rel_path).open("r", encoding="utf-8") as f:
            for doc in yaml.safe_load_all(f):
                if isinstance(doc, dict) and doc.get("kind") == "Application":
                    metadata = doc.get("metadata")
                    name = metadata.get("name") if isinstance(metadata, dict) else None
                    if isinstance(name, str) and name:
                        if name in seen:
                            duplicates.append(f"{name}: {seen[name]} vs {rel_path}")
                        else:
                            seen[name] = rel_path
    return duplicates


def check_argocd_ports() -> list[str]:
    ports = parse_glossary_ports()
    issues: list[str] = []
    for rel_path in _git_argocd_yaml_paths():
        with (REPO_ROOT / rel_path).open("r", encoding="utf-8") as f:
            for doc in yaml.safe_load_all(f):
                if not isinstance(doc, dict) or doc.get("kind") != "Rollout":
                    continue
                issues.extend(check_container_ports(doc, rel_path, ports, label="has"))
    return issues


def check_topic_dlt() -> list[str]:
    topic_names_path = REPO_ROOT / "contracts/src/main/java/com/instacommerce/contracts/topics/TopicNames.java"
    src = topic_names_path.read_text(encoding="utf-8")
    vals = dict(re.findall(r'public static final String ([A-Z0-9_]+)\s*=\s*"([^"]+)"', src))
    dlt_rhs = " ".join(re.findall(r"_DLT\s*=\s*([^;]+);", src))
    return [
        f"{k} {v}"
        for k, v in vals.items()
        if v.endswith(".events") and k not in dlt_rhs and f"{v}.DLT" not in src
    ]


CHECKS: dict[str, Callable[[], list[str]]] = {
    "istio-policies": check_istio_policies,
    "dependabot": check_dependabot,
    "argocd-apps": check_argocd_apps,
    "argocd-ports": check_argocd_ports,
    "dockerfile-ports": verify_dockerfile_ports,
    "k8s-compose-ports": check_k8s_compose_ports,
    "dbt-refs": check_dbt_refs,
    "topic-dlt": check_topic_dlt,
}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run monorepo consistency checks.")
    parser.add_argument("check", choices=sorted(CHECKS.keys()))
    args = parser.parse_args(argv)

    issues = CHECKS[args.check]()
    if issues:
        print("\n".join(issues))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
