"""Tests for model artifact integrity verification."""

import os
import tempfile
from pathlib import Path

from ml.serving.integrity import (
    CHECKSUM_FILENAME,
    compute_sha256,
    generate_checksums,
    parse_checksums_file,
    verify_model_integrity,
)


def test_compute_sha256():
    with tempfile.NamedTemporaryFile(mode="wb", delete=False, suffix=".bin") as f:
        f.write(b"test model weights")
        path = Path(f.name)
    try:
        digest = compute_sha256(path)
        assert len(digest) == 64
        assert digest == compute_sha256(path)  # deterministic
    finally:
        os.unlink(path)


def test_generate_and_verify_checksums():
    with tempfile.TemporaryDirectory() as tmpdir:
        model_dir = Path(tmpdir)
        (model_dir / "model.onnx").write_bytes(b"fake onnx weights")
        (model_dir / "metadata.json").write_text('{"name": "test"}')

        generate_checksums(model_dir)
        assert (model_dir / CHECKSUM_FILENAME).exists()

        assert verify_model_integrity(model_dir, "test-model") is True


def test_verify_detects_tampering():
    with tempfile.TemporaryDirectory() as tmpdir:
        model_dir = Path(tmpdir)
        (model_dir / "model.onnx").write_bytes(b"original weights")
        generate_checksums(model_dir)

        # Tamper with the model file
        (model_dir / "model.onnx").write_bytes(b"tampered weights")

        assert verify_model_integrity(model_dir, "test-model") is False


def test_verify_missing_checksums_nonstrict():
    with tempfile.TemporaryDirectory() as tmpdir:
        model_dir = Path(tmpdir)
        (model_dir / "model.onnx").write_bytes(b"weights")
        # No CHECKSUMS.sha256 file
        assert verify_model_integrity(model_dir, "test-model", strict=False) is True


def test_verify_missing_checksums_strict():
    with tempfile.TemporaryDirectory() as tmpdir:
        model_dir = Path(tmpdir)
        (model_dir / "model.onnx").write_bytes(b"weights")
        assert verify_model_integrity(model_dir, "test-model", strict=True) is False


def test_verify_missing_artifact():
    with tempfile.TemporaryDirectory() as tmpdir:
        model_dir = Path(tmpdir)
        (model_dir / "model.onnx").write_bytes(b"weights")
        generate_checksums(model_dir)

        # Delete the artifact
        os.unlink(model_dir / "model.onnx")

        assert verify_model_integrity(model_dir, "test-model") is False


def test_parse_checksums_ignores_comments():
    with tempfile.TemporaryDirectory() as tmpdir:
        checksums_path = Path(tmpdir) / CHECKSUM_FILENAME
        checksums_path.write_text("# comment\nabc123  model.onnx\n\n")
        result = parse_checksums_file(checksums_path)
        assert result == {"model.onnx": "abc123"}
