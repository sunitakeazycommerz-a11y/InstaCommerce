"""Unit tests for InstaCommerce LSP walkthrough engine and JSON-RPC client."""
from __future__ import annotations

import json
import os
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Any

import pytest

LSP_DIR = Path(__file__).resolve().parent
if str(LSP_DIR) not in sys.path:
    sys.path.insert(0, str(LSP_DIR))

import code_walkthrough as cw
import lsp_client as lc


class _PipeStreamWrapper:
    """Minimal file-descriptor-backed stream wrapper for testing LSPClient._read_response."""

    def __init__(self, fd: int):
        self._fd = fd

    def fileno(self) -> int:
        return self._fd

    def read(self, size: int = -1) -> bytes:
        return os.read(self._fd, size if size > 0 else 65536)


class _DummyProcess:
    def __init__(self, stdout_fd: int):
        self.stdout: Any = _PipeStreamWrapper(stdout_fd)
        self.stdin: Any = None


def test_walk_true_case_and_canonicalize_path(tmp_path: Path) -> None:
    repo = tmp_path / "InstaCommerce"
    target = repo / "services" / "order-service" / "src" / "OrderService.java"
    target.parent.mkdir(parents=True)
    target.write_text("class OrderService {}\n", encoding="utf-8")

    # On case-insensitive filesystems (macOS APFS), lowercase lookup resolves to true directory case.
    wrong_case = tmp_path / "instacommerce" / "services" / "order-service" / "src" / "orderservice.java"
    if wrong_case.exists():
        canonical = Path(lc.canonicalize_path(wrong_case))
        assert canonical == target.resolve()

    walked = lc._walk_true_case(tmp_path / "instacommerce" / "services" / "order-service" / "src" / "orderservice.java")
    assert walked.name == "OrderService.java"
    assert walked.parts[-5:] == ("InstaCommerce", "services", "order-service", "src", "OrderService.java")


def test_resolve_gradle_project_and_display_path_handle_case_mismatch(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    repo = tmp_path / "InstaCommerce"
    svc = repo / "services" / "order-service"
    java_file = svc / "src" / "main" / "java" / "OrderService.java"
    java_file.parent.mkdir(parents=True)
    (svc / "build.gradle.kts").write_text("", encoding="utf-8")
    java_file.write_text("class OrderService {}\n", encoding="utf-8")

    wrong_case_repo = tmp_path / "instacommerce"
    if wrong_case_repo.exists():
        monkeypatch.setattr(cw, "REPO_ROOT", str(wrong_case_repo))
        assert cw._resolve_gradle_project(str(java_file)) == ":services:order-service"
        assert cw._display_path(str(java_file), "OrderService.java") == (
            "services/order-service/src/main/java/OrderService.java"
        )
    else:
        monkeypatch.setattr(cw, "REPO_ROOT", str(repo))
        assert cw._resolve_gradle_project(str(java_file)) == ":services:order-service"
        assert cw._display_path(str(java_file), "OrderService.java") == (
            "services/order-service/src/main/java/OrderService.java"
        )


def test_check_go_fails_on_diagnostics_even_with_zero_returncode(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    repo = tmp_path / "InstaCommerce"
    go_dir = repo / "services" / "outbox-relay-service"
    go_dir.mkdir(parents=True)
    go_file = go_dir / "main.go"
    go_file.write_text("package main\n", encoding="utf-8")

    monkeypatch.setattr(cw, "REPO_ROOT", str(repo))
    monkeypatch.setattr(cw.shutil, "which", lambda _name: "/usr/local/bin/gopls")

    captured_calls: list[dict[str, Any]] = []

    def fake_run(
        cmd: list[str], cwd: str, env: dict[str, str], capture_output: bool, text: bool
    ) -> subprocess.CompletedProcess[str]:
        captured_calls.append({"cmd": cmd, "cwd": cwd, "env": env})
        return subprocess.CompletedProcess(
            args=cmd,
            returncode=0,
            stdout="",
            stderr="Error: Error loading workspace folders (expected 1, got 0)",
        )

    monkeypatch.setattr(cw.subprocess, "run", fake_run)

    result = cw._check_go(str(go_file))
    assert result["status"] == "fail"
    assert result["exit_code"] == 1
    assert len(result["diagnostics"]) == 1
    expected_cwd = lc.canonicalize_path(repo)
    assert captured_calls[0]["cwd"] == expected_cwd
    assert captured_calls[0]["env"]["PWD"] == expected_cwd

    with pytest.raises(SystemExit) as exc:
        cw.cmd_check(str(go_file), as_json=True)
    assert exc.value.code == 1


def test_check_go_passes_when_no_diagnostics_and_zero_returncode(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    repo = tmp_path / "InstaCommerce"
    go_dir = repo / "services" / "outbox-relay-service"
    go_dir.mkdir(parents=True)
    go_file = go_dir / "main.go"
    go_file.write_text("package main\n", encoding="utf-8")

    monkeypatch.setattr(cw, "REPO_ROOT", str(repo))
    monkeypatch.setattr(cw.shutil, "which", lambda _name: "/usr/local/bin/gopls")
    monkeypatch.setattr(
        cw.subprocess,
        "run",
        lambda cmd, cwd, env, capture_output, text: subprocess.CompletedProcess(
            args=cmd, returncode=0, stdout="", stderr=""
        ),
    )

    result = cw._check_go(str(go_file))
    assert result["status"] == "pass"
    assert result["exit_code"] == 0
    assert result["diagnostics"] == []


def test_lsp_client_read_response_reassembles_fragmented_pipe_and_skips_notifications() -> None:
    read_fd, write_fd = os.pipe()
    client = lc.LSPClient(["dummy"], ".")
    client.process = _DummyProcess(read_fd)  # type: ignore[assignment]

    notif_body = json.dumps({"jsonrpc": "2.0", "method": "window/logMessage", "params": {"message": "hi"}}).encode()
    notif_frame = f"Content-Type: application/vscode-jsonrpc; charset=utf-8\r\ncontent-length: {len(notif_body)}\r\n\r\n".encode() + notif_body
    resp_body = json.dumps({"jsonrpc": "2.0", "id": 7, "result": {"ok": True, "padding": "x" * 2048}}).encode()
    resp_frame = f"Content-Length: {len(resp_body)}\r\n\r\n".encode() + resp_body
    payload = notif_frame + resp_frame

    def writer() -> None:
        try:
            for idx in range(0, len(payload), 13):
                os.write(write_fd, payload[idx : idx + 13])
                time.sleep(0.002)
        finally:
            os.close(write_fd)

    t = threading.Thread(target=writer, daemon=True)
    t.start()
    try:
        msg = client._read_response(expected_id=7, timeout=3.0)
        assert msg is not None
        assert msg["id"] == 7
        assert msg["result"]["ok"] is True
    finally:
        t.join(timeout=2.0)
        os.close(read_fd)


def test_lsp_client_read_response_times_out_when_pipe_stalls() -> None:
    read_fd, write_fd = os.pipe()
    client = lc.LSPClient(["dummy"], ".")
    client.process = _DummyProcess(read_fd)  # type: ignore[assignment]

    start = time.monotonic()
    try:
        msg = client._read_response(expected_id=1, timeout=0.15)
        elapsed = time.monotonic() - start
        assert msg is None
        assert elapsed < 1.0
    finally:
        os.close(write_fd)
        os.close(read_fd)
