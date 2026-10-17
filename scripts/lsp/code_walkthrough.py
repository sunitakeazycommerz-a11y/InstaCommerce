#!/usr/bin/env python3
"""
InstaCommerce Code Walkthrough Engine
CLI utility powered by LSP servers (gopls, pyright, jdtls) and semantic AST parsing.
"""

import argparse
import json
import os
import sys
from pathlib import Path

# Add script directory to sys.path
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, SCRIPT_DIR)

from ast_parser import parse_any
from lsp_client import (
    get_lsp_status,
    query_gopls_definition,
    query_gopls_references,
    query_gopls_symbols,
)

REPO_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, "..", ".."))


def print_banner():
    print("=" * 80)
    print("   InstaCommerce LSP Code Walkthrough & Navigation Engine")
    print("   Powered by: gopls (Go) | pyright (Python) | jdtls (Java)")
    print("=" * 80)


def cmd_status():
    """Outputs status of all installed LSP servers."""
    print_banner()
    status = get_lsp_status()
    print("\n[+] Installed Language Server Protocols:\n")

    # Go
    go_s = status.get("golang", {})
    if go_s.get("installed"):
        print(f"  • Go (gopls):     ✅ INSTALLED  [{go_s.get('version')}]")
        print(f"    Path:            {go_s.get('path')}")
    else:
        print(f"  • Go (gopls):     ❌ NOT FOUND  (Install: {go_s.get('install_hint')})")

    # Python
    py_s = status.get("python", {})
    if py_s.get("installed"):
        print(f"  • Python (pyright): ✅ INSTALLED  [{py_s.get('version')}]")
        print(f"    Pyright:         {py_s.get('pyright_path')}")
        print(f"    LangServer:      {py_s.get('langserver_path')}")
    else:
        print(f"  • Python (pyright): ❌ NOT FOUND  (Install: {py_s.get('install_hint')})")

    # Java
    jv_s = status.get("java", {})
    if jv_s.get("installed"):
        print(f"  • Java (jdtls):    ✅ INSTALLED  [{jv_s.get('version')}]")
        print(f"    Path:            {jv_s.get('path')}")
    else:
        print(f"  • Java (jdtls):    ❌ NOT FOUND  (Install: {jv_s.get('install_hint')})")

    print("\n[+] Workspace Root:", REPO_ROOT)
    print("=" * 80)


def cmd_outline(file_path: str):
    """Outputs a clean symbol outline of a file."""
    if not os.path.isfile(file_path):
        print(f"Error: File not found: {file_path}")
        sys.exit(1)

    ext = os.path.splitext(file_path)[1].lower()

    # If Go and gopls is available, show gopls symbols first
    if ext == ".go":
        syms = query_gopls_symbols(file_path)
        if syms:
            print(f"\n[+] Gopls Symbol Outline for: {file_path}\n")
            print(syms)
            return

    parsed = parse_any(file_path)
    lang = parsed.get("language", "Unknown")
    print(f"\n[+] Symbol Outline [{lang}]: {file_path}")
    print(f"    Lines: {parsed.get('total_lines', 'N/A')}")
    if parsed.get("package"):
        print(f"    Package: {parsed.get('package')}")

    # Classes / Structs
    if parsed.get("classes"):
        print("\n  [Classes / Interfaces / Enums]:")
        for c in parsed["classes"]:
            ann = f" [{', '.join(c.get('annotations', []))}]" if c.get("annotations") else ""
            print(f"    L{c['line']:<4} {c.get('kind', 'class')} {c['name']}{ann}")

    if parsed.get("structs"):
        print("\n  [Structs]:")
        for s in parsed["structs"]:
            print(f"    L{s['line']:<4} struct {s['name']}")

    # Endpoints
    if parsed.get("endpoints"):
        print("\n  [API Endpoints / Routes]:")
        for ep in parsed["endpoints"]:
            if "annotation" in ep:
                print(f"    L{ep['line']:<4} {ep['method']}() -> {ep['annotation']}")
            else:
                print(f"    L{ep['line']:<4} {ep['function']}() -> {ep['decorator']}")

    # Methods / Functions
    methods = parsed.get("methods") or parsed.get("functions") or []
    if methods:
        print("\n  [Methods & Functions]:")
        for m in methods:
            ret = m.get("return_type") or m.get("returns") or ""
            ret_str = f" : {ret}" if ret else ""
            recv = f"({m.get('receiver')}) " if m.get("receiver") else ""
            ann = f" [{', '.join(m.get('annotations', []))}]" if m.get("annotations") else ""
            print(f"    L{m['line']:<4} {recv}{m['name']}({m.get('parameters', '')}){ret_str}{ann}")

    # Event emissions
    if parsed.get("event_emissions"):
        print("\n  [Domain Events / Messaging]:")
        for ev in parsed["event_emissions"][:10]:
            print(f"    L{ev['line']:<4} {ev['content']}")


def cmd_definition(file_path: str, line: int, col: int):
    """Jumps to symbol definition."""
    ext = os.path.splitext(file_path)[1].lower()
    if ext == ".go":
        res = query_gopls_definition(file_path, line, col)
        if res:
            print(f"\n[+] Definition (via gopls):\n{res}\n")
            return

    print(f"Resolving definition for {file_path}:{line}:{col}...")
    print("Using AST / Symbol Indexer:")
    parsed = parse_any(file_path)
    print(json.dumps(parsed, indent=2))


def cmd_references(file_path: str, line: int, col: int):
    """Finds all references to symbol."""
    ext = os.path.splitext(file_path)[1].lower()
    if ext == ".go":
        res = query_gopls_references(file_path, line, col)
        if res:
            print(f"\n[+] References (via gopls):\n{res}\n")
            return

    print(f"Finding references for {file_path}:{line}:{col}...")


def cmd_walkthrough(file_path: str):
    """Generates an in-depth code walkthrough report for a source file."""
    if not os.path.isfile(file_path):
        print(f"Error: File not found: {file_path}")
        sys.exit(1)

    parsed = parse_any(file_path)
    rel_path = os.path.relpath(file_path, REPO_ROOT)

    print("=" * 80)
    print(f"   CODE WALKTHROUGH: {rel_path}")
    print("=" * 80)

    # Detect role
    role = "Domain Component"
    if "controller" in rel_path.lower():
        role = "Inbound API / REST Controller"
    elif "workflow" in rel_path.lower():
        role = "Temporal Distributed Saga Workflow"
    elif "consumer" in rel_path.lower() or "listener" in rel_path.lower():
        role = "Asynchronous Event Consumer"
    elif "service" in rel_path.lower():
        role = "Domain Application Service"
    elif "repository" in rel_path.lower():
        role = "Persistence Repository"
    elif "model" in rel_path.lower() or "entity" in rel_path.lower():
        role = "Domain Entity / Aggregate Root"

    print(f"• Language:         {parsed.get('language')}")
    print(f"• Inferred Role:    {role}")
    print(f"• Package/Module:   {parsed.get('package', 'default')}")
    print(f"• Total Lines:      {parsed.get('total_lines')}")

    # Endpoints
    if parsed.get("endpoints"):
        print("\n[+] Ingress Endpoints:")
        for ep in parsed["endpoints"]:
            if "annotation" in ep:
                print(f"   • L{ep['line']:<4} {ep['annotation']} -> {ep['method']}()")
            else:
                print(f"   • L{ep['line']:<4} {ep['decorator']} -> {ep['function']}()")

    # Classes / Types
    types = parsed.get("classes") or parsed.get("structs") or []
    if types:
        print("\n[+] Key Types & Invariants:")
        for t in types:
            ann = f" ({', '.join(t.get('annotations', []))})" if t.get("annotations") else ""
            print(f"   • L{t['line']:<4} {t.get('kind', 'struct')} {t['name']}{ann}")

    # Methods
    methods = parsed.get("methods") or parsed.get("functions") or []
    if methods:
        print("\n[+] Method & Execution Map:")
        for m in methods:
            recv = f"({m.get('receiver')}) " if m.get("receiver") else ""
            ann = f" [{', '.join(m.get('annotations', []))}]" if m.get("annotations") else ""
            print(f"   • L{m['line']:<4} {recv}{m['name']}() {ann}")

    # Events
    if parsed.get("event_emissions"):
        print("\n[+] Transactional Events & Outbox Activity:")
        for ev in parsed["event_emissions"][:8]:
            print(f"   • L{ev['line']:<4} {ev['content']}")

    print("\n" + "=" * 80)


def cmd_service(service_name: str):
    """Performs an architectural code tour of an entire service."""
    svc_dir = os.path.join(REPO_ROOT, "services", service_name)
    if not os.path.isdir(svc_dir):
        print(f"Error: Service directory not found: {svc_dir}")
        sys.exit(1)

    print("=" * 80)
    print(f"   SERVICE WALKTHROUGH: {service_name}")
    print(f"   Path: services/{service_name}")
    print("=" * 80)

    # Detect stack
    is_java = os.path.isfile(os.path.join(svc_dir, "build.gradle.kts"))
    is_go = os.path.isfile(os.path.join(svc_dir, "go.mod"))
    is_python = os.path.isfile(os.path.join(svc_dir, "requirements.txt"))

    stack = "Java 21 / Spring Boot 4.0" if is_java else ("Go 1.22" if is_go else ("Python 3.13 / FastAPI" if is_python else "Unknown"))
    print(f"• Runtime Stack:     {stack}")

    # Count files
    files = list(Path(svc_dir).rglob("*"))
    source_files = [f for f in files if f.suffix in (".java", ".go", ".py")]
    print(f"• Total Source Files: {len(source_files)}")

    # Key directories
    print("\n[+] Structural Walkthrough:")
    for root, dirs, _ in os.walk(svc_dir):
        rel = os.path.relpath(root, svc_dir)
        if rel in ("src/main/java", "internal", "app", "cmd", "pkg", "src/main/resources/db/migration"):
            sub_files = [f for f in os.listdir(root) if not f.startswith(".")]
            print(f"   📁 {rel}/ ({len(sub_files)} items)")

    # Print endpoints found across service
    endpoints = []
    for sf in source_files:
        try:
            parsed = parse_any(str(sf))
            for ep in parsed.get("endpoints", []):
                ep["file"] = os.path.relpath(str(sf), svc_dir)
                endpoints.append(ep)
        except Exception:
            pass

    if endpoints:
        print(f"\n[+] Exposed Endpoints ({len(endpoints)} total):")
        for ep in endpoints[:15]:
            desc = ep.get("annotation") or ep.get("decorator")
            m = ep.get("method") or ep.get("function")
            print(f"   • {desc} -> {m}()  [{ep['file']}:{ep['line']}]")

    print("\n" + "=" * 80)


def main():
    parser = argparse.ArgumentParser(description="InstaCommerce LSP Code Walkthrough Utility")
    subparsers = parser.add_subparsers(dest="command", required=True)

    # status
    subparsers.add_parser("status", help="Check installed LSP servers and paths")

    # outline
    p_outline = subparsers.add_parser("outline", help="Generate symbol outline of a file")
    p_outline.add_argument("file", help="Path to source file (.java, .go, .py)")

    # def
    p_def = subparsers.add_parser("def", help="Jump to definition (LSP)")
    p_def.add_argument("file", help="Source file")
    p_def.add_argument("line", type=int, help="Line number (1-based)")
    p_def.add_argument("col", type=int, default=1, nargs="?", help="Column number (1-based)")

    # refs
    p_refs = subparsers.add_parser("refs", help="Find symbol references (LSP)")
    p_refs.add_argument("file", help="Source file")
    p_refs.add_argument("line", type=int, help="Line number (1-based)")
    p_refs.add_argument("col", type=int, default=1, nargs="?", help="Column number (1-based)")

    # walkthrough
    p_walk = subparsers.add_parser("walkthrough", help="Generate detailed code walkthrough of a file")
    p_walk.add_argument("file", help="Path to source file (.java, .go, .py)")

    # service
    p_svc = subparsers.add_parser("service", help="Walkthrough an entire microservice")
    p_svc.add_argument("name", help="Service name (e.g. order-service, outbox-relay-service)")

    args = parser.parse_args()

    if args.command == "status":
        cmd_status()
    elif args.command == "outline":
        cmd_outline(args.file)
    elif args.command == "def":
        cmd_definition(args.file, args.line, args.col)
    elif args.command == "refs":
        cmd_references(args.file, args.line, args.col)
    elif args.command == "walkthrough":
        cmd_walkthrough(args.file)
    elif args.command == "service":
        cmd_service(args.name)


if __name__ == "__main__":
    main()

