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

_DBT_COMMENT_RE = re.compile(r"\{#.*?#\}", re.S)
_DBT_SOURCE_RE = re.compile(
    r"\{\{-?\s*source\(\s*['\"]([^'\"]+)['\"]\s*,\s*['\"]([^'\"]+)['\"]\s*\)\s*-?\}\}"
)
_DBT_REF_RE = re.compile(
    r"\{\{-?\s*ref\(\s*['\"]([^'\"]+)['\"](?:\s*,\s*['\"]([^'\"]+)['\"])?\s*\)\s*-?\}\}"
)
_DBT_CONFIG_RE = re.compile(r"\{\{-?\s*config\((.*?)\)\s*-?\}\}", re.S)
_DBT_MAT_RE = re.compile(r"\bmaterialized\s*=\s*['\"]([^'\"]+)['\"]")
_DBT_IS_INCREMENTAL_RE = re.compile(r"\bis_incremental\s*\(\s*\)")


def _git_argocd_yaml_paths() -> list[str]:
    out = subprocess.check_output(
        ["git", "ls-files", "--", "argocd/*.yaml", "argocd/**/*.yaml"],
        cwd=REPO_ROOT,
        text=True,
    )
    return sorted(set(out.splitlines()))


def _parse_glossary_ports() -> dict[str, int]:
    glossary = (REPO_ROOT / "GLOSSARY.md").read_text(encoding="utf-8")
    return {
        svc: int(port)
        for svc, port in re.findall(r"^\| `([a-z0-9-]+)` \| [^|]+ \| ([0-9]+) \|", glossary, re.M)
    }


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
    ports = _parse_glossary_ports()
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
    ports = _parse_glossary_ports()
    issues: list[str] = []

    for svc, expected_int in sorted(ports.items()):
        expected = str(expected_int)
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


def _collect_dbt_sources(models_root: Path) -> set[tuple[str, str]]:
    declared: set[tuple[str, str]] = set()
    yaml_files = sorted(
        p for p in models_root.rglob("*") if p.is_file() and p.suffix in {".yml", ".yaml"}
    )
    for yml_path in yaml_files:
        with yml_path.open("r", encoding="utf-8") as f:
            loaded: Any = yaml.safe_load(f) or {}
        if not isinstance(loaded, dict):
            continue
        raw_sources = loaded.get("sources")
        if not isinstance(raw_sources, list):
            continue
        for src in raw_sources:
            if not isinstance(src, dict):
                continue
            src_name = src.get("name")
            tables = src.get("tables", [])
            if not isinstance(src_name, str) or not isinstance(tables, list):
                continue
            for tbl in tables:
                if isinstance(tbl, dict) and isinstance(tbl.get("name"), str):
                    declared.add((src_name, tbl["name"]))
    return declared


def _resolve_dbt_project_materialization(
    project_models_cfg: dict[str, Any], parts: tuple[str, ...]
) -> str | None:
    node: dict[str, Any] = project_models_cfg
    mat: str | None = None
    for key in ("+materialized", "materialized"):
        if isinstance(node.get(key), str):
            mat = node[key]
    for part in parts:
        child = node.get(part)
        if not isinstance(child, dict):
            break
        node = child
        for key in ("+materialized", "materialized"):
            if isinstance(node.get(key), str):
                mat = node[key]
    return mat


def _extract_sql_materialization(sql_text: str) -> str | None:
    for cfg_body in _DBT_CONFIG_RE.findall(sql_text):
        match = _DBT_MAT_RE.search(cfg_body)
        if match:
            return match.group(1)
    return None


def check_dbt_refs() -> list[str]:
    """Verify dbt source/ref targets and is_incremental() guards on incremental models."""
    dbt_root = REPO_ROOT / "data-platform" / "dbt"
    models_root = dbt_root / "models"
    if not models_root.is_dir():
        return ["missing data-platform/dbt/models directory"]

    project_name = ""
    project_models_cfg: dict[str, Any] = {}
    project_file = dbt_root / "dbt_project.yml"
    if project_file.is_file():
        with project_file.open("r", encoding="utf-8") as f:
            loaded_proj: Any = yaml.safe_load(f) or {}
        if isinstance(loaded_proj, dict):
            raw_name = loaded_proj.get("name")
            if isinstance(raw_name, str):
                project_name = raw_name
            models_section = loaded_proj.get("models")
            if isinstance(models_section, dict):
                raw_proj_cfg = models_section.get(project_name)
                if isinstance(raw_proj_cfg, dict):
                    project_models_cfg = raw_proj_cfg

    declared_sources = _collect_dbt_sources(models_root)
    sql_files = sorted(models_root.rglob("*.sql"))
    model_names = {p.stem for p in sql_files}
    issues: list[str] = []

    for sql_path in sql_files:
        rel = str(sql_path.relative_to(REPO_ROOT))
        clean_text = _DBT_COMMENT_RE.sub("", sql_path.read_text(encoding="utf-8"))

        for src_name, tbl_name in _DBT_SOURCE_RE.findall(clean_text):
            if (src_name, tbl_name) not in declared_sources:
                issues.append(f"{rel}: unresolved source {src_name}.{tbl_name}")

        for first, second in _DBT_REF_RE.findall(clean_text):
            if second:
                if (not project_name or first == project_name) and second not in model_names:
                    issues.append(f"{rel}: unresolved ref {second}")
            elif first not in model_names:
                issues.append(f"{rel}: unresolved ref {first}")

        sql_mat = _extract_sql_materialization(clean_text)
        proj_mat = _resolve_dbt_project_materialization(
            project_models_cfg, sql_path.relative_to(models_root).with_suffix("").parts
        )
        effective_mat = sql_mat or proj_mat
        if effective_mat == "incremental" and not _DBT_IS_INCREMENTAL_RE.search(clean_text):
            issues.append(f"{rel}: incremental model missing is_incremental() guard")

    return issues


def _parse_port_spec_range(spec: str) -> list[int]:
    if "-" in spec:
        start_s, end_s = spec.split("-", 1)
        if start_s.isdigit() and end_s.isdigit():
            start, end = int(start_s), int(end_s)
            if start <= end:
                return list(range(start, end + 1))
        return []
    return [int(spec)] if spec.isdigit() else []


def _extract_compose_host_ports(port_entry: Any) -> list[int]:
    if isinstance(port_entry, dict):
        published = port_entry.get("published")
        if isinstance(published, int):
            return [published]
        if isinstance(published, str):
            return _parse_port_spec_range(published.strip())
        return []
    if not isinstance(port_entry, str):
        return []
    cleaned = port_entry.split("/", 1)[0].strip()
    parts = cleaned.split(":")
    if len(parts) == 2:
        return _parse_port_spec_range(parts[0])
    if len(parts) == 3:
        return _parse_port_spec_range(parts[1])
    return []


def _check_k8s_ports(glossary_ports: dict[str, int]) -> list[str]:
    k8s_dir = REPO_ROOT / "k8s"
    if not k8s_dir.is_dir():
        return []
    issues: list[str] = []
    manifest_paths = sorted(
        p for p in k8s_dir.iterdir() if p.is_file() and p.suffix in {".yaml", ".yml"}
    )
    for manifest_path in manifest_paths:
        rel = str(manifest_path.relative_to(REPO_ROOT))
        with manifest_path.open("r", encoding="utf-8") as f:
            for doc in yaml.safe_load_all(f):
                if not isinstance(doc, dict):
                    continue
                kind = doc.get("kind")
                metadata = doc.get("metadata")
                meta_name = metadata.get("name") if isinstance(metadata, dict) else None
                spec = doc.get("spec")
                if not isinstance(spec, dict):
                    continue
                if kind in {"Deployment", "StatefulSet", "DaemonSet"}:
                    template = spec.get("template")
                    pod_spec = template.get("spec") if isinstance(template, dict) else None
                    containers = pod_spec.get("containers") if isinstance(pod_spec, dict) else None
                    if not isinstance(containers, list):
                        continue
                    for container in containers:
                        if not isinstance(container, dict):
                            continue
                        cname = container.get("name")
                        expected = glossary_ports.get(str(cname)) if isinstance(cname, str) else None
                        if expected is None:
                            continue
                        raw_ports = container.get("ports", [])
                        cports = [
                            cp.get("containerPort")
                            for cp in raw_ports
                            if isinstance(cp, dict) and "containerPort" in cp
                        ]
                        if expected not in cports:
                            issues.append(f"{rel}:{cname} containerPort {cports} expected {expected}")
                elif kind == "Service":
                    selector = spec.get("selector")
                    sel_app = selector.get("app") if isinstance(selector, dict) else None
                    svc_key = (
                        meta_name
                        if isinstance(meta_name, str) and meta_name in glossary_ports
                        else (sel_app if isinstance(sel_app, str) and sel_app in glossary_ports else None)
                    )
                    if svc_key is None:
                        continue
                    expected = glossary_ports[svc_key]
                    raw_ports = spec.get("ports", [])
                    sports = [
                        sp.get("port")
                        for sp in raw_ports
                        if isinstance(sp, dict) and "port" in sp
                    ]
                    if expected not in sports:
                        issues.append(f"{rel}:Service/{svc_key} ports {sports} expected {expected}")
    return issues


def _check_compose_ports_and_tags(glossary_ports: dict[str, int]) -> list[str]:
    compose_path = REPO_ROOT / "docker-compose.yml"
    if not compose_path.is_file():
        return ["missing docker-compose.yml"]
    with compose_path.open("r", encoding="utf-8") as f:
        loaded: Any = yaml.safe_load(f) or {}
    if not isinstance(loaded, dict):
        return ["docker-compose.yml: invalid YAML root"]

    reserved_ports: dict[int, str] = {port: svc for svc, port in glossary_ports.items()}
    services = loaded.get("services")
    if not isinstance(services, dict):
        return []

    issues: list[str] = []
    for svc_name, raw_cfg in services.items():
        if not isinstance(raw_cfg, dict):
            continue
        raw_ports = raw_cfg.get("ports", [])
        if isinstance(raw_ports, list):
            for entry in raw_ports:
                for host_port in _extract_compose_host_ports(entry):
                    owner = reserved_ports.get(host_port)
                    if owner is not None:
                        issues.append(
                            f"docker-compose.yml:{svc_name} host port {host_port} collides with {owner}"
                        )
        image = raw_cfg.get("image")
        if isinstance(image, str):
            last_segment = image.rsplit("/", 1)[-1]
            if image.endswith(":latest") or (":" not in last_segment and "@sha256:" not in image):
                issues.append(f"docker-compose.yml:{svc_name} uses unpinned image '{image}'")
    return issues


def check_k8s_compose_ports() -> list[str]:
    """Verify k8s/*.yaml ports match GLOSSARY.md and docker-compose.yml avoids port collisions and :latest."""
    glossary_ports = _parse_glossary_ports()
    return _check_k8s_ports(glossary_ports) + _check_compose_ports_and_tags(glossary_ports)


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
