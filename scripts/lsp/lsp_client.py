#!/usr/bin/env python3
"""
InstaCommerce LSP Client & Code Navigation Protocol Wrapper
Supports Go (gopls), Python (pyright-langserver / pyright), and Java (jdtls).
"""

import json
import os
import select
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Dict, List, Optional

try:
    import fcntl as _fcntl
except ImportError:  # pragma: no cover - non-POSIX platforms
    _fcntl = None

_F_GETPATH = getattr(_fcntl, "F_GETPATH", 50) if (_fcntl is not None and sys.platform == "darwin") else None


def _walk_true_case(path: Path) -> Path:
    """Resolve exact directory entry casing component-by-component on case-insensitive filesystems."""
    parts = path.parts
    if not parts:
        return path
    current = Path(parts[0])
    for part in parts[1:]:
        candidate = current / part
        if current.is_dir():
            try:
                entries = list(os.scandir(current))
                exact = next((e.name for e in entries if e.name == part), None)
                if exact is not None:
                    current = current / exact
                    continue
                lowered = part.lower()
                matched = next((e.name for e in entries if e.name.lower() == lowered), None)
                if matched is not None:
                    current = current / matched
                    continue
            except OSError:
                pass
        current = candidate
    return current


def canonicalize_path(path: str | os.PathLike[str]) -> str:
    """Return canonical path with symlinks resolved and true macOS filesystem casing."""
    real = os.path.realpath(os.path.abspath(os.fspath(path)))
    if _F_GETPATH is not None and _fcntl is not None:
        p = Path(real)
        suffix_parts: List[str] = []
        probe = p
        while not probe.exists() and probe != probe.parent:
            suffix_parts.append(probe.name)
            probe = probe.parent
        if probe.exists():
            try:
                fd = os.open(str(probe), os.O_RDONLY)
                try:
                    raw_path = _fcntl.fcntl(fd, _F_GETPATH, b"\x00" * 1024)
                    resolved_base = os.fsdecode(bytes(raw_path).split(b"\x00", 1)[0])
                    if resolved_base:
                        out = Path(resolved_base)
                        for part in reversed(suffix_parts):
                            out = out / part
                        return str(_walk_true_case(out))
                finally:
                    os.close(fd)
            except OSError:
                pass
    return str(_walk_true_case(Path(real)))


class LSPClient:
    """Manages LSP JSON-RPC communication over stdio."""

    def __init__(self, command: List[str], root_dir: str):
        self.command = command
        self.root_dir = canonicalize_path(root_dir)
        self.process: Optional[subprocess.Popen[bytes]] = None
        self._msg_id = 0
        self._read_buf = bytearray()

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
            self._read_buf.clear()
            return True
        except Exception as e:
            sys.stderr.write(f"Failed to start LSP process {self.command}: {e}\n")
            return False

    def send_request(
        self,
        method: str,
        params: Optional[Dict[str, Any]] = None,
        timeout: float = 8.0,
    ) -> Optional[Dict[str, Any]]:
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
            return self._read_response(expected_id=self._msg_id, timeout=timeout)
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

    def _read_chunk(self, deadline: float) -> bytes:
        """Read available bytes from stdout pipe without blocking past deadline."""
        if not self.process or not self.process.stdout:
            return b""
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return b""
        stdout = self.process.stdout
        try:
            fd = stdout.fileno()
        except (AttributeError, OSError, ValueError):
            return stdout.read(65536)
        ready, _, _ = select.select([fd], [], [], remaining)
        if not ready:
            return b""
        return os.read(fd, 65536)

    @staticmethod
    def _find_header_end(buf: bytearray) -> tuple[int, int]:
        crlf = buf.find(b"\r\n\r\n")
        lf = buf.find(b"\n\n")
        if crlf != -1 and (lf == -1 or crlf < lf):
            return crlf, 4
        if lf != -1:
            return lf, 2
        return -1, 0

    @staticmethod
    def _parse_content_length(header_bytes: bytes) -> Optional[int]:
        for raw_line in header_bytes.decode("ascii", errors="ignore").splitlines():
            if ":" not in raw_line:
                continue
            name, value = raw_line.split(":", 1)
            if name.strip().lower() == "content-length":
                try:
                    length = int(value.strip())
                    return length if length >= 0 else None
                except ValueError:
                    return None
        return None

    def _read_response(self, expected_id: int, timeout: float = 8.0) -> Optional[Dict[str, Any]]:
        """Reads framed JSON-RPC messages from stdout until expected_id arrives or timeout expires."""
        if not self.process or not self.process.stdout:
            return None

        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            header_end, sep_len = self._find_header_end(self._read_buf)
            while header_end == -1:
                chunk = self._read_chunk(deadline)
                if not chunk:
                    return None
                self._read_buf.extend(chunk)
                header_end, sep_len = self._find_header_end(self._read_buf)

            header_bytes = bytes(self._read_buf[:header_end])
            del self._read_buf[: header_end + sep_len]

            content_len = self._parse_content_length(header_bytes)
            if content_len is None:
                continue

            while len(self._read_buf) < content_len:
                chunk = self._read_chunk(deadline)
                if not chunk:
                    return None
                self._read_buf.extend(chunk)

            body_bytes = bytes(self._read_buf[:content_len])
            del self._read_buf[:content_len]

            try:
                msg = json.loads(body_bytes.decode("utf-8", errors="replace"))
            except json.JSONDecodeError:
                continue

            if isinstance(msg, dict) and msg.get("id") == expected_id:
                return msg

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
                for stream in (self.process.stdin, self.process.stdout, self.process.stderr):
                    if stream is not None:
                        try:
                            stream.close()
                        except Exception:
                            pass
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


def _gopls_env(cwd: Optional[str]) -> Dict[str, str]:
    env = dict(os.environ)
    if cwd:
        env["PWD"] = cwd
    return env


def query_gopls_definition(file_path: str, line: int, col: int) -> Optional[str]:
    """Queries gopls definition using built-in CLI."""
    gopls_bin = shutil.which("gopls")
    if not gopls_bin:
        return None
    try:
        canon_file = canonicalize_path(file_path)
        target = f"{canon_file}:{line}:{col}"
        cwd = os.path.dirname(canon_file) if os.path.exists(os.path.dirname(canon_file)) else None
        out = subprocess.check_output(
            [gopls_bin, "definition", target], cwd=cwd, env=_gopls_env(cwd), text=True, timeout=5
        )
        return out.strip()
    except Exception as e:
        return f"Error querying gopls definition: {e}"


def query_gopls_references(file_path: str, line: int, col: int) -> Optional[str]:
    """Queries gopls references using built-in CLI."""
    gopls_bin = shutil.which("gopls")
    if not gopls_bin:
        return None
    try:
        canon_file = canonicalize_path(file_path)
        target = f"{canon_file}:{line}:{col}"
        cwd = os.path.dirname(canon_file) if os.path.exists(os.path.dirname(canon_file)) else None
        out = subprocess.check_output(
            [gopls_bin, "references", target], cwd=cwd, env=_gopls_env(cwd), text=True, timeout=5
        )
        return out.strip()
    except Exception as e:
        return f"Error querying gopls references: {e}"


def query_gopls_symbols(file_path: str) -> Optional[str]:
    """Queries gopls symbols in a Go file."""
    gopls_bin = shutil.which("gopls")
    if not gopls_bin:
        return None
    try:
        canon_file = canonicalize_path(file_path)
        cwd = os.path.dirname(canon_file) if os.path.exists(os.path.dirname(canon_file)) else None
        out = subprocess.check_output(
            [gopls_bin, "symbols", canon_file], cwd=cwd, env=_gopls_env(cwd), text=True, timeout=5
        )
        return out.strip()
    except Exception as e:
        return f"Error querying gopls symbols: {e}"

