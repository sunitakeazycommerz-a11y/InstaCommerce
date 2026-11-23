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
    verify_dockerfile_base_images,
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


def _collect_container_images(doc: dict[str, Any]) -> list[tuple[str, str]]:
    spec = doc.get("spec")
    template = spec.get("template") if isinstance(spec, dict) else None
    pod_spec = template.get("spec") if isinstance(template, dict) else None
    if not isinstance(pod_spec, dict):
        return []
    images: list[tuple[str, str]] = []
    for key in ("containers", "initContainers"):
        raw_list = pod_spec.get(key)
        if not isinstance(raw_list, list):
            continue
        for c in raw_list:
            if isinstance(c, dict) and isinstance(c.get("image"), str):
                cname = str(c.get("name", key))
                images.append((cname, c["image"]))
    return images


def check_argocd_tags() -> list[str]:
    issues: list[str] = []
    for rel_path in _git_argocd_yaml_paths():
        with (REPO_ROOT / rel_path).open("r", encoding="utf-8") as f:
            for doc in yaml.safe_load_all(f):
                if not isinstance(doc, dict):
                    continue
                for cname, image in _collect_container_images(doc):
                    last_segment = image.rsplit("/", 1)[-1]
                    if image.endswith(":latest") or (":" not in last_segment and "@sha256:" not in image):
                        issues.append(f"{rel_path}:{cname} uses unpinned image '{image}'")
    return issues


def check_doc_drift() -> list[str]:
    """Detect banned stale architectural claims in live root and architecture docs."""
    doc_rules: list[tuple[str, list[tuple[str, str]]]] = [
        (
            "README.md",
            [
                (r"search-service.*Elasticsearch", "claims search-service uses Elasticsearch (uses PostgreSQL tsvector FTS)"),
                (r"spring-cloud-gcp\s+5\.1\.0", "claims spring-cloud-gcp 5.1.0 (root build uses 8.3.1)"),
                (r"Go Services\s*\(7\b", "claims 7 Go services (actual: 8 Go services + go-shared)"),
                (r"creates\s+17\s+DBs|17\s+databases", "claims init-dbs.sql creates 17 DBs"),
                (r"ai-inference-service.*--port\s+8000", "claims ai-inference-service runs on port 8000 (actual: 8101)"),
                (r"mobile-bff-service.*🟡|admin-gateway-service.*🟡|stub endpoints", "marks mobile-bff/admin-gateway as stub/scaffold"),
                (r"AI governance.*not yet implemented", "claims AI governance is not yet implemented"),
            ],
        ),
        (
            "docs/README.md",
            [
                (r"\b7 Go\b", "claims 7 Go services (actual: 8 Go services + go-shared)"),
                (r"20 Java modules \(contracts \+ 19", "stale settings.gradle.kts module count"),
                (r"28 deployed services", "claims 28 deployed services (actual: 30)"),
                (r"not runtime-enforced", "claims AI governance is not runtime-enforced"),
            ],
        ),
        (
            "GLOSSARY.md",
            [
                (r"Python 3\.13", "claims Python 3.13 instead of Python 3.12"),
            ],
        ),
        (
            "docs/architecture/HLD.md",
            [
                (r"search-service.*Elasticsearch", "claims search-service uses Elasticsearch"),
                (r"Spring Boot 3\b", "claims Spring Boot 3 (actual: 4.0.0)"),
                (r"Go 1\.22\b", "claims Go 1.22 (actual: 1.25)"),
                (r"PostgreSQL 15\b", "claims PostgreSQL 15 (actual: 16)"),
            ],
        ),
    ]
    abs_user_path_re = re.compile(r"/Users/[A-Za-z0-9_.-]+/")
    issues: list[str] = []

    for rel_path, rules in doc_rules:
        full_path = REPO_ROOT / rel_path
        if not full_path.is_file():
            continue
        text = full_path.read_text(encoding="utf-8")
        for pattern, reason in rules:
            if re.search(pattern, text, re.IGNORECASE):
                issues.append(f"{rel_path}: {reason}")
        if abs_user_path_re.search(text):
            issues.append(f"{rel_path}: contains hardcoded local /Users/... path")

    agents_path = REPO_ROOT / "AGENTS.md"
    if agents_path.is_file() and abs_user_path_re.search(agents_path.read_text(encoding="utf-8")):
        issues.append("AGENTS.md: contains hardcoded local /Users/... path")

    repo_truth_dir = REPO_ROOT / "docs" / "repo-truth"
    if repo_truth_dir.is_dir():
        for md in sorted(repo_truth_dir.glob("*.md")):
            text = md.read_text(encoding="utf-8")
            rel = str(md.relative_to(REPO_ROOT))
            if "source tree is effectively absent" in text:
                issues.append(f"{rel}: stale claim 'source tree is effectively absent'")
            if abs_user_path_re.search(text):
                issues.append(f"{rel}: contains hardcoded local /Users/... path")

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
    "argocd-tags": check_argocd_tags,
    "dockerfile-ports": verify_dockerfile_ports,
    "dockerfile-base-images": verify_dockerfile_base_images,
    "k8s-compose-ports": check_k8s_compose_ports,
    "dbt-refs": check_dbt_refs,
    "topic-dlt": check_topic_dlt,
    "doc-drift": check_doc_drift,
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
