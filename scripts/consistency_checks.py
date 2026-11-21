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

REPO_ROOT = Path(__file__).resolve().parent.parent
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
    ports: dict[str, int] = {}
    glossary_path = REPO_ROOT / "GLOSSARY.md"
    with glossary_path.open("r", encoding="utf-8") as f:
        for line in f:
            match = re.match(r"^\| `([a-z0-9-]+)` \| [^|]+ \| ([0-9]+) \|", line)
            if match:
                ports[match.group(1)] = int(match.group(2))

    issues: list[str] = []
    for rel_path in _git_argocd_yaml_paths():
        with (REPO_ROOT / rel_path).open("r", encoding="utf-8") as f:
            for doc in yaml.safe_load_all(f):
                if not isinstance(doc, dict) or doc.get("kind") != "Rollout":
                    continue
                spec = doc.get("spec", {})
                template = spec.get("template", {}) if isinstance(spec, dict) else {}
                pod_spec = template.get("spec", {}) if isinstance(template, dict) else {}
                containers = pod_spec.get("containers", []) if isinstance(pod_spec, dict) else []
                if not isinstance(containers, list):
                    continue
                for container in containers:
                    if not isinstance(container, dict):
                        continue
                    cname = container.get("name")
                    expected = ports.get(str(cname)) if isinstance(cname, str) else None
                    if expected is None:
                        continue
                    raw_ports = container.get("ports", [])
                    cports = [
                        cp.get("containerPort")
                        for cp in raw_ports
                        if isinstance(cp, dict) and "containerPort" in cp
                    ]
                    if expected not in cports:
                        issues.append(f"{rel_path}:{cname} has {cports} expected {expected}")
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


def verify_dockerfile_ports() -> list[str]:
    """Verify every service Dockerfile EXPOSE, env port, and healthcheck matches GLOSSARY.md."""
    glossary = (REPO_ROOT / "GLOSSARY.md").read_text(encoding="utf-8")
    ports = re.findall(r"^\| `([a-z0-9-]+)` \| [^|]+ \| ([0-9]+) \|", glossary, re.M)
    issues: list[str] = []

    for svc, expected in sorted(ports):
        df = REPO_ROOT / "services" / svc / "Dockerfile"
        if not df.is_file():
            issues.append(f"{svc}: missing Dockerfile")
            continue
        text = df.read_text(encoding="utf-8")
        expose_match = re.search(r"^EXPOSE\s+([^\n]+)", text, re.M)
        if not expose_match:
            issues.append(f"{svc}: missing EXPOSE directive")
        else:
            exposed = re.findall(r"\b([0-9]+)\b", expose_match.group(1))
            if not exposed or exposed[0] != expected:
                issues.append(f"{svc}: EXPOSE {expose_match.group(1).strip()} expected {expected}")

        for env_key, env_port in re.findall(r"^ENV\s+(SERVER_PORT|PORT)=([0-9]+)", text, re.M):
            if env_port != expected:
                issues.append(f"{svc}: ENV {env_key}={env_port} expected {expected}")

        health_addr = re.search(r'^ENV\s+HEALTH_ADDR=":([0-9]+)"', text, re.M)
        allowed_hc_port = health_addr.group(1) if health_addr else expected
        for hc_port in re.findall(r"http://localhost:([0-9]+)\b", text):
            if hc_port != allowed_hc_port:
                issues.append(f"{svc}: HEALTHCHECK localhost:{hc_port} expected {allowed_hc_port}")

        if (REPO_ROOT / "services" / svc / "build.gradle.kts").is_file():
            if f"ENV SERVER_PORT={expected}" not in text:
                issues.append(f"{svc}: Java Dockerfile missing ENV SERVER_PORT={expected}")

    return issues


CHECKS: dict[str, Callable[[], list[str]]] = {
    "istio-policies": check_istio_policies,
    "dependabot": check_dependabot,
    "argocd-apps": check_argocd_apps,
    "argocd-ports": check_argocd_ports,
    "dockerfile-ports": verify_dockerfile_ports,
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
