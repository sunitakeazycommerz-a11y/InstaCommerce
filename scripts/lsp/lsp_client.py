#!/usr/bin/env python3
"""
InstaCommerce LSP Client & Code Navigation Protocol Wrapper
Supports Go (gopls), Python (pyright-langserver / pyright), and Java (jdtls).
"""

import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


class LSPClient:
    """Manages LSP JSON-RPC communication over stdio."""

    def __init__(self, command: List[str], root_dir: str):
        self.command = command
        self.root_dir = os.path.abspath(root_dir)
        self.process: Optional[subprocess.Popen] = None
        self._msg_id = 0

    def start(self, timeout: float = 10.0) -> bool:
        """Starts the language server process."""
        try:
            self.process = subprocess.Popen(
                self.command,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                bufsize=0,
            )
            return True
        except Exception as e:
            sys.stderr.write(f"Failed to start LSP process {self.command}: {e}\n")
            return False

    def send_request(self, method: str, params: Optional[Dict[str, Any]] = None) -> Optional[Dict[str, Any]]:
        """Sends a JSON-RPC request and waits for response."""
        if not self.process or not self.process.stdin:
            return None

        self._msg_id += 1
        request = {
            "jsonrpc": "2.0",
            "id": self._msg_id,
            "method": method,
            "params": params or {},
        }
        content = json.dumps(request).encode("utf-8")
        header = f"Content-Length: {len(content)}\r\n\r\n".encode("utf-8")

        try:
            self.process.stdin.write(header + content)
            self.process.stdin.flush()
            return self._read_response(expected_id=self._msg_id)
        except Exception as e:
            sys.stderr.write(f"LSP request error ({method}): {e}\n")
            return None

    def send_notification(self, method: str, params: Optional[Dict[str, Any]] = None) -> None:
        """Sends a JSON-RPC notification (no response expected)."""
        if not self.process or not self.process.stdin:
            return

        notification = {
            "jsonrpc": "2.0",
            "method": method,
            "params": params or {},
        }
        content = json.dumps(notification).encode("utf-8")
        header = f"Content-Length: {len(content)}\r\n\r\n".encode("utf-8")

        try:
            self.process.stdin.write(header + content)
            self.process.stdin.flush()
        except Exception:
            pass

    def _read_response(self, expected_id: int, timeout: float = 8.0) -> Optional[Dict[str, Any]]:
        """Reads JSON-RPC message from stdout matching expected_id."""
        if not self.process or not self.process.stdout:
            return None

        start_time = time.time()
        while time.time() - start_time < timeout:
            header_line = self.process.stdout.readline()
            if not header_line:
                break
            header_str = header_line.decode("utf-8", errors="ignore")
            if header_str.startswith("Content-Length:"):
                try:
                    content_len = int(header_str.split(":")[1].strip())
                    # Consume remaining empty header lines
                    while True:
                        empty_line = self.process.stdout.readline()
                        if empty_line in (b"\r\n", b"\n", b""):
                            break

                    body = self.process.stdout.read(content_len)
                    msg = json.loads(body.decode("utf-8", errors="ignore"))
                    if msg.get("id") == expected_id:
                        return msg
                except Exception:
                    continue

        return None

    def initialize(self) -> Optional[Dict[str, Any]]:
        """Executes the LSP initialize handshake."""
        root_uri = Path(self.root_dir).as_uri()
        params = {
            "processId": os.getpid(),
            "rootUri": root_uri,
            "capabilities": {
                "textDocument": {
                    "documentSymbol": {"hierarchicalDocumentSymbolSupport": True},
                    "definition": {"dynamicRegistration": False},
                    "references": {"dynamicRegistration": False},
                    "hover": {"contentFormat": ["markdown", "plaintext"]},
                },
                "workspace": {"symbol": {"dynamicRegistration": False}},
            },
        }
        res = self.send_request("initialize", params)
        if res:
            self.send_notification("initialized", {})
        return res

    def close(self) -> None:
        """Gracefully shuts down language server."""
        if self.process:
            try:
                self.send_request("shutdown")
                self.send_notification("exit")
            except Exception:
                pass
            finally:
                if self.process.poll() is None:
                    self.process.terminate()
                    try:
                        self.process.wait(timeout=2.0)
                    except subprocess.TimeoutExpired:
                        self.process.kill()


def get_lsp_status() -> Dict[str, Any]:
    """Inspects installed LSP servers and environment paths."""
    status = {}

    # Go: gopls
    gopls_bin = shutil.which("gopls")
    if gopls_bin:
        try:
            ver_out = subprocess.check_output([gopls_bin, "version"], text=True, timeout=3).strip()
            status["golang"] = {"installed": True, "path": gopls_bin, "version": ver_out.splitlines()[0]}
        except Exception as e:
            status["golang"] = {"installed": True, "path": gopls_bin, "error": str(e)}
    else:
        status["golang"] = {"installed": False, "install_hint": "brew install gopls"}

    # Python: pyright / pyright-langserver
    pyright_bin = shutil.which("pyright")
    pyright_ls = shutil.which("pyright-langserver")
    if pyright_bin or pyright_ls:
        ver = "unknown"
        if pyright_bin:
            try:
                ver = subprocess.check_output([pyright_bin, "--version"], text=True, timeout=3).strip()
            except Exception:
                pass
        status["python"] = {
            "installed": True,
            "pyright_path": pyright_bin,
            "langserver_path": pyright_ls,
            "version": ver,
        }
    else:
        status["python"] = {"installed": False, "install_hint": "brew install pyright"}

    # Java: jdtls
    jdtls_bin = shutil.which("jdtls")
    if jdtls_bin:
        status["java"] = {
            "installed": True,
            "path": jdtls_bin,
            "version": "Eclipse JDT Language Server (Homebrew)",
        }
    else:
        status["java"] = {"installed": False, "install_hint": "brew install jdtls"}

    return status


def query_gopls_definition(file_path: str, line: int, col: int) -> Optional[str]:
    """Queries gopls definition using built-in CLI."""
    gopls_bin = shutil.which("gopls")
    if not gopls_bin:
        return None
    try:
        target = f"{os.path.abspath(file_path)}:{line}:{col}"
        out = subprocess.check_output([gopls_bin, "definition", target], text=True, timeout=5)
        return out.strip()
    except Exception as e:
        return f"Error querying gopls definition: {e}"


def query_gopls_references(file_path: str, line: int, col: int) -> Optional[str]:
    """Queries gopls references using built-in CLI."""
    gopls_bin = shutil.which("gopls")
    if not gopls_bin:
        return None
    try:
        target = f"{os.path.abspath(file_path)}:{line}:{col}"
        out = subprocess.check_output([gopls_bin, "references", target], text=True, timeout=5)
        return out.strip()
    except Exception as e:
        return f"Error querying gopls references: {e}"


def query_gopls_symbols(file_path: str) -> Optional[str]:
    """Queries gopls symbols in a Go file."""
    gopls_bin = shutil.which("gopls")
    if not gopls_bin:
        return None
    try:
        out = subprocess.check_output([gopls_bin, "symbols", os.path.abspath(file_path)], text=True, timeout=5)
        return out.strip()
    except Exception as e:
        return f"Error querying gopls symbols: {e}"

