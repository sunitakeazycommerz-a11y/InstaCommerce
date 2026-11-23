#!/usr/bin/env python3
"""Dockerfile, Kubernetes/Compose, and dbt consistency check helpers."""
from __future__ import annotations

import re
from pathlib import Path
from typing import Any

import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent

_DBT_COMMENT_RE = re.compile(r"\{#.*?#\}", re.S)
_JINJA_TAG_RE = re.compile(r"\{\{-?(.*?)-?\}\}|\{%-?(.*?)-?%\}", re.S)
_DBT_SOURCE_RE = re.compile(r"\bsource\(\s*['\"]([^'\"]+)['\"]\s*,\s*['\"]([^'\"]+)['\"]\s*\)")
_DBT_REF_RE = re.compile(r"\bref\(\s*['\"]([^'\"]+)['\"](?:\s*,\s*['\"]([^'\"]+)['\"])?\s*\)")
_DBT_CONFIG_RE = re.compile(r"\bconfig\((.*)\)", re.S)
_DBT_MAT_RE = re.compile(r"\bmaterialized\s*=\s*['\"]([^'\"]+)['\"]")
_DBT_IS_INCREMENTAL_RE = re.compile(r"\bis_incremental\s*\(\s*\)")


def _extract_port_values(raw_ports: Any, key: str) -> list[Any]:
    if not isinstance(raw_ports, list):
        return []
    return [entry.get(key) for entry in raw_ports if isinstance(entry, dict) and key in entry]


def parse_glossary_ports() -> dict[str, int]:
    glossary = (REPO_ROOT / "GLOSSARY.md").read_text(encoding="utf-8")
    return {
        svc: int(port)
        for svc, port in re.findall(r"^\| `([a-z0-9-]+)` \| [^|]+ \| ([0-9]+) \|", glossary, re.M)
    }


def check_container_ports(
    doc: dict[str, Any],
    rel_path: str,
    glossary_ports: dict[str, int],
    label: str = "has",
) -> list[str]:
    spec = doc.get("spec")
    template = spec.get("template") if isinstance(spec, dict) else None
    pod_spec = template.get("spec") if isinstance(template, dict) else None
    containers = pod_spec.get("containers") if isinstance(pod_spec, dict) else None
    if not isinstance(containers, list):
        return []

    issues: list[str] = []
    for container in containers:
        if not isinstance(container, dict):
            continue
        cname = container.get("name")
        expected = glossary_ports.get(cname) if isinstance(cname, str) else None
        if expected is None:
            continue
        cports = _extract_port_values(container.get("ports", []), "containerPort")
        if expected not in cports:
            issues.append(f"{rel_path}:{cname} {label} {cports} expected {expected}")
    return issues


def verify_dockerfile_ports() -> list[str]:
    """Verify every service Dockerfile EXPOSE, env port, and healthcheck matches GLOSSARY.md."""
    ports = parse_glossary_ports()
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
    mat = next((node[k] for k in ("+materialized", "materialized") if isinstance(node.get(k), str)), None)
    for part in parts:
        child = node.get(part)
        if not isinstance(child, dict):
            break
        node = child
        mat = next((node[k] for k in ("+materialized", "materialized") if isinstance(node.get(k), str)), mat)
    return mat


def _extract_jinja_tags(sql_text: str) -> list[str]:
    return [
        m.group(1) if m.group(1) is not None else m.group(2)
        for m in _JINJA_TAG_RE.finditer(sql_text)
    ]


def _extract_sql_materialization(jinja_tags: list[str]) -> str | None:
    for tag in jinja_tags:
        cfg_match = _DBT_CONFIG_RE.search(tag)
        if not cfg_match:
            continue
        mat_match = _DBT_MAT_RE.search(cfg_match.group(1))
        if mat_match:
            return mat_match.group(1)
    return None


def check_dbt_refs(dbt_root: Path | None = None) -> list[str]:
    """Verify dbt source/ref targets and is_incremental() guards on incremental models."""
    resolved_dbt_root = dbt_root or (REPO_ROOT / "data-platform" / "dbt")
    models_root = resolved_dbt_root / "models"
    if not models_root.is_dir():
        return ["missing data-platform/dbt/models directory"]

    project_name = ""
    project_models_cfg: dict[str, Any] = {}
    project_file = resolved_dbt_root / "dbt_project.yml"
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
        rel = str(sql_path.relative_to(REPO_ROOT)) if sql_path.is_relative_to(REPO_ROOT) else str(sql_path)
        clean_text = _DBT_COMMENT_RE.sub("", sql_path.read_text(encoding="utf-8"))
        jinja_tags = _extract_jinja_tags(clean_text)

        for tag in jinja_tags:
            for src_name, tbl_name in _DBT_SOURCE_RE.findall(tag):
                if (src_name, tbl_name) not in declared_sources:
                    issues.append(f"{rel}: unresolved source {src_name}.{tbl_name}")

            for first, second in _DBT_REF_RE.findall(tag):
                if second:
                    if (not project_name or first == project_name) and second not in model_names:
                        issues.append(f"{rel}: unresolved ref {second}")
                elif first not in model_names:
                    issues.append(f"{rel}: unresolved ref {first}")

        sql_mat = _extract_sql_materialization(jinja_tags)
        proj_mat = _resolve_dbt_project_materialization(
            project_models_cfg, sql_path.relative_to(models_root).with_suffix("").parts
        )
        effective_mat = sql_mat or proj_mat
        if effective_mat == "incremental" and not any(_DBT_IS_INCREMENTAL_RE.search(t) for t in jinja_tags):
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


def _check_k8s_ports(glossary_ports: dict[str, int], k8s_root: Path | None = None) -> list[str]:
    k8s_dir = k8s_root or (REPO_ROOT / "k8s")
    if not k8s_dir.is_dir():
        return []
    issues: list[str] = []
    manifest_paths = sorted(
        p for p in k8s_dir.rglob("*.y*ml") if p.is_file() and p.suffix in {".yaml", ".yml"}
    )
    for manifest_path in manifest_paths:
        rel = str(manifest_path.relative_to(REPO_ROOT)) if manifest_path.is_relative_to(REPO_ROOT) else str(manifest_path)
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
                    issues.extend(check_container_ports(doc, rel, glossary_ports, label="containerPort"))
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
                    sports = _extract_port_values(spec.get("ports", []), "port")
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
    """Verify k8s/**/*.yaml ports match GLOSSARY.md and docker-compose.yml avoids port collisions and :latest."""
    glossary_ports = parse_glossary_ports()
    return _check_k8s_ports(glossary_ports) + _check_compose_ports_and_tags(glossary_ports)


_EXPECTED_JAVA_BASE = "eclipse-temurin:21-jre-alpine"
_EXPECTED_GO_BUILDER = "golang:1.25-alpine"
_EXPECTED_GO_RUNTIME = "alpine:3.23"
_EXPECTED_PYTHON_BASE = "python:3.12-slim"


def verify_dockerfile_base_images() -> list[str]:
    """Verify standardized Dockerfile base images and non-root runtime user across Java/Go/Python services."""
    services_root = REPO_ROOT / "services"
    if not services_root.is_dir():
        return ["missing services/ directory"]

    issues: list[str] = []
    for svc_dir in sorted(p for p in services_root.iterdir() if p.is_dir()):
        svc = svc_dir.name
        if svc in {"build", "go-shared"}:
            continue
        df = svc_dir / "Dockerfile"
        if not df.is_file():
            issues.append(f"{svc}: missing Dockerfile")
            continue
        text = df.read_text(encoding="utf-8")
        from_images = re.findall(r"^FROM\s+([^\s]+)", text, re.M)
        if not from_images:
            issues.append(f"{svc}: Dockerfile missing FROM directive")
            continue

        if (svc_dir / "build.gradle.kts").is_file():
            runtime_img = from_images[-1]
            if runtime_img != _EXPECTED_JAVA_BASE:
                issues.append(f"{svc}: Java Dockerfile uses '{runtime_img}' expected '{_EXPECTED_JAVA_BASE}'")
            user_match = re.findall(r"^USER\s+([^\s]+)", text, re.M)
            if not user_match or user_match[-1] != "app":
                issues.append(f"{svc}: Java Dockerfile must run as USER app (found {user_match})")
        elif (svc_dir / "go.mod").is_file():
            if len(from_images) < 2:
                issues.append(f"{svc}: Go Dockerfile must use multi-stage build")
            else:
                if from_images[0] != _EXPECTED_GO_BUILDER:
                    issues.append(f"{svc}: Go builder uses '{from_images[0]}' expected '{_EXPECTED_GO_BUILDER}'")
                if from_images[-1] != _EXPECTED_GO_RUNTIME:
                    issues.append(f"{svc}: Go runtime uses '{from_images[-1]}' expected '{_EXPECTED_GO_RUNTIME}'")
            user_match = re.findall(r"^USER\s+([^\s]+)", text, re.M)
            if not user_match or user_match[-1] != "app":
                issues.append(f"{svc}: Go Dockerfile must run as USER app (found {user_match})")
        elif (svc_dir / "requirements.txt").is_file():
            runtime_img = from_images[-1]
            if runtime_img != _EXPECTED_PYTHON_BASE:
                issues.append(f"{svc}: Python Dockerfile uses '{runtime_img}' expected '{_EXPECTED_PYTHON_BASE}'")
            user_match = re.findall(r"^USER\s+([^\s]+)", text, re.M)
            if not user_match or user_match[-1] != "app":
                issues.append(f"{svc}: Python Dockerfile must run as USER app (found {user_match})")

    return issues
