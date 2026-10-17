#!/usr/bin/env python3
"""
InstaCommerce AST & Semantic Symbol Parser
Extracts structural elements, annotations, routes, and events for Java, Python, and Go files.
"""

import ast
import os
import re
from typing import Any, Dict, List, Optional


class JavaParser:
    """Parses Java files to extract packages, classes, methods, annotations, and events."""

    CLASS_PATTERN = re.compile(
        r"((?:@\w+(?:\([^)]*\))?\s+)*)(public|protected|private)?\s*(abstract|final|static)?\s*(class|interface|enum|record)\s+(\w+)(?:<[^>]+>)?(?:\s+extends\s+[\w<>, ]+)?(?:\s+implements\s+[\w<>, ]+)?\s*\{",
        re.MULTILINE,
    )
    METHOD_PATTERN = re.compile(
        r"((?:@\w+(?:\([^)]*\))?\s+)*)(public|protected|private|static|\s)+([\w<>\[\], ?]+)\s+(\w+)\s*\(([^)]*)\)\s*(?:throws\s+[\w, ]+)?\s*\{",
        re.MULTILINE,
    )
    ANNOTATION_PATTERN = re.compile(r"@(\w+)(?:\(([^)]*)\))?")

    @classmethod
    def parse_file(cls, path: str) -> Dict[str, Any]:
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            lines = f.readlines()
        content = "".join(lines)

        package = ""
        imports = []
        for line in lines:
            line_s = line.strip()
            if line_s.startswith("package "):
                package = line_s.replace("package ", "").replace(";", "").strip()
            elif line_s.startswith("import "):
                imports.append(line_s.replace("import ", "").replace(";", "").strip())

        classes = []
        for i, line in enumerate(lines, 1):
            match = re.search(r"(class|interface|enum|record)\s+(\w+)", line)
            if match and "{" in line and not line.strip().startswith("//"):
                kind, name = match.groups()
                # Find annotations preceding this line
                annotations = []
                back_idx = i - 2
                while back_idx >= 0 and lines[back_idx].strip().startswith("@"):
                    annotations.insert(0, lines[back_idx].strip())
                    back_idx -= 1

                classes.append({
                    "name": name,
                    "kind": kind,
                    "line": i,
                    "annotations": annotations,
                })

        methods = []
        method_regex = re.compile(
            r"^\s*(public|protected|private)?\s*(static|final|synchronized)?\s*([\w<>\[\]]+)\s+(\w+)\s*\(([^)]*)\)\s*(?:throws\s+[\w, ]+)?\s*\{"
        )
        for i, line in enumerate(lines, 1):
            stripped = line.strip()
            if stripped.startswith("//") or stripped.startswith("*"):
                continue
            m = method_regex.search(line)
            if m:
                vis, mod, ret, mname, params = m.groups()
                if mname not in ("if", "for", "while", "switch", "catch"):
                    # Extract preceding annotations
                    annotations = []
                    b_idx = i - 2
                    while b_idx >= 0 and lines[b_idx].strip().startswith("@"):
                        annotations.insert(0, lines[b_idx].strip())
                        b_idx -= 1
                    methods.append({
                        "name": mname,
                        "return_type": ret,
                        "parameters": params.strip(),
                        "visibility": vis or "package-private",
                        "line": i,
                        "annotations": annotations,
                    })

        # Identify key InstaCommerce patterns
        endpoints = []
        for m in methods:
            for ann in m["annotations"]:
                if any(x in ann for x in ("@GetMapping", "@PostMapping", "@PutMapping", "@DeleteMapping", "@RequestMapping")):
                    endpoints.append({
                        "method": m["name"],
                        "line": m["line"],
                        "annotation": ann,
                    })

        events = []
        for i, line in enumerate(lines, 1):
            if any(ev in line for ev in ("outbox", "EventEnvelope", "KafkaTemplate", "publishEvent", "DomainEvent")):
                events.append({"line": i, "content": line.strip()})

        return {
            "language": "Java",
            "file": os.path.basename(path),
            "path": path,
            "package": package,
            "import_count": len(imports),
            "classes": classes,
            "methods": methods,
            "endpoints": endpoints,
            "event_emissions": events,
            "total_lines": len(lines),
        }


class PythonParser:
    """Parses Python files using python ast module to extract classes, functions, and endpoints."""

    @classmethod
    def parse_file(cls, path: str) -> Dict[str, Any]:
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            content = f.read()
            lines = content.splitlines()

        try:
            tree = ast.parse(content, filename=path)
        except Exception as e:
            return {"language": "Python", "file": os.path.basename(path), "error": str(e)}

        classes = []
        functions = []
        endpoints = []

        for node in ast.walk(tree):
            if isinstance(node, ast.ClassDef):
                decorators = [ast.unparse(d) for d in node.decorator_list]
                bases = [ast.unparse(b) for b in node.bases]
                classes.append({
                    "name": node.name,
                    "line": node.lineno,
                    "bases": bases,
                    "decorators": decorators,
                })
            elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                decorators = [ast.unparse(d) for d in node.decorator_list]
                args = [a.arg for a in node.args.args]
                ret = ast.unparse(node.returns) if node.returns else "None"
                is_async = isinstance(node, ast.AsyncFunctionDef)

                f_info = {
                    "name": node.name,
                    "line": node.lineno,
                    "args": args,
                    "return_type": ret,
                    "async": is_async,
                    "decorators": decorators,
                }
                functions.append(f_info)

                # Check for FastAPI route decorators
                for dec in decorators:
                    if any(dec.startswith(p) for p in ("app.", "router.", "@app.", "@router.")):
                        endpoints.append({
                            "function": node.name,
                            "line": node.lineno,
                            "decorator": dec,
                        })

        return {
            "language": "Python",
            "file": os.path.basename(path),
            "path": path,
            "classes": classes,
            "functions": functions,
            "endpoints": endpoints,
            "total_lines": len(lines),
        }


class GoParser:
    """Parses Go files to extract package, structs, interfaces, methods, and functions."""

    @classmethod
    def parse_file(cls, path: str) -> Dict[str, Any]:
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            lines = f.readlines()

        package = ""
        structs = []
        interfaces = []
        functions = []
        methods = []

        func_regex = re.compile(r"^func\s+(?:\(([^)]+)\)\s+)?(\w+)\s*\(([^)]*)\)(?:\s+([^{]+))?\s*\{")
        type_regex = re.compile(r"^type\s+(\w+)\s+(struct|interface)")

        for i, line in enumerate(lines, 1):
            line_s = line.strip()
            if line_s.startswith("package "):
                package = line_s.replace("package ", "").strip()
            elif type_m := type_regex.match(line_s):
                t_name, t_kind = type_m.groups()
                if t_kind == "struct":
                    structs.append({"name": t_name, "line": i})
                else:
                    interfaces.append({"name": t_name, "line": i})
            elif func_m := func_regex.match(line_s):
                recv, fname, params, ret = func_m.groups()
                if recv:
                    methods.append({
                        "name": fname,
                        "receiver": recv.strip(),
                        "parameters": params.strip(),
                        "returns": (ret or "").strip(),
                        "line": i,
                    })
                else:
                    functions.append({
                        "name": fname,
                        "parameters": params.strip(),
                        "returns": (ret or "").strip(),
                        "line": i,
                    })

        return {
            "language": "Go",
            "file": os.path.basename(path),
            "path": path,
            "package": package,
            "structs": structs,
            "interfaces": interfaces,
            "functions": functions,
            "methods": methods,
            "total_lines": len(lines),
        }


def parse_any(file_path: str) -> Dict[str, Any]:
    """Auto-detects file type and parses using appropriate parser."""
    ext = os.path.splitext(file_path)[1].lower()
    if ext == ".java":
        return JavaParser.parse_file(file_path)
    elif ext == ".py":
        return PythonParser.parse_file(file_path)
    elif ext == ".go":
        return GoParser.parse_file(file_path)
    else:
        return {"error": f"Unsupported file type: {ext}"}

