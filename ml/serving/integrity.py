"""Model artifact integrity verification.

Verifies SHA-256 checksums of model weights before loading to prevent
serving compromised or corrupted model artifacts. Each model version
directory must include a CHECKSUMS.sha256 file containing hex-encoded
SHA-256 digests of all artifact files.

Expected format of CHECKSUMS.sha256:
    <hex_digest>  <filename>
"""

import hashlib
import logging
from pathlib import Path
from typing import Optional

from prometheus_client import Counter

logger = logging.getLogger(__name__)

_INTEGRITY_PASS = Counter(
    "ml_model_integrity_pass_total",
    "Model artifacts that passed integrity verification",
    labelnames=["model"],
    namespace="ml",
)
_INTEGRITY_FAIL = Counter(
    "ml_model_integrity_fail_total",
    "Model artifacts that failed integrity verification",
    labelnames=["model"],
    namespace="ml",
)
_INTEGRITY_SKIP = Counter(
    "ml_model_integrity_skip_total",
    "Model loads that skipped integrity verification (no CHECKSUMS.sha256)",
    labelnames=["model"],
    namespace="ml",
)

CHECKSUM_FILENAME = "CHECKSUMS.sha256"


def compute_sha256(file_path: Path, chunk_size: int = 8192) -> str:
    h = hashlib.sha256()
    with open(file_path, "rb") as f:
        while True:
            chunk = f.read(chunk_size)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def parse_checksums_file(checksums_path: Path) -> dict[str, str]:
    result = {}
    with open(checksums_path) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split(None, 1)
            if len(parts) == 2:
                expected_hash, filename = parts
                result[filename.strip()] = expected_hash.lower()
    return result


def verify_model_integrity(
    model_dir: Path,
    model_name: str,
    strict: bool = False,
) -> bool:
    checksums_path = model_dir / CHECKSUM_FILENAME

    if not checksums_path.exists():
        if strict:
            logger.error(
                "integrity.missing_checksums model=%s dir=%s strict=true",
                model_name, model_dir,
            )
            _INTEGRITY_FAIL.labels(model=model_name).inc()
            return False
        logger.warning(
            "integrity.no_checksums model=%s dir=%s -- skipping verification",
            model_name, model_dir,
        )
        _INTEGRITY_SKIP.labels(model=model_name).inc()
        return True

    expected = parse_checksums_file(checksums_path)
    if not expected:
        logger.warning("integrity.empty_checksums model=%s", model_name)
        _INTEGRITY_SKIP.labels(model=model_name).inc()
        return True

    all_valid = True
    for filename, expected_hash in expected.items():
        artifact_path = model_dir / filename
        if not artifact_path.exists():
            logger.error(
                "integrity.missing_artifact model=%s file=%s",
                model_name, filename,
            )
            _INTEGRITY_FAIL.labels(model=model_name).inc()
            all_valid = False
            continue

        actual_hash = compute_sha256(artifact_path)
        if actual_hash != expected_hash:
            logger.error(
                "integrity.hash_mismatch model=%s file=%s expected=%s actual=%s",
                model_name, filename, expected_hash, actual_hash,
            )
            _INTEGRITY_FAIL.labels(model=model_name).inc()
            all_valid = False
        else:
            logger.info(
                "integrity.verified model=%s file=%s hash=%s",
                model_name, filename, actual_hash,
            )

    if all_valid:
        _INTEGRITY_PASS.labels(model=model_name).inc()
        logger.info("integrity.all_passed model=%s artifacts=%d", model_name, len(expected))

    return all_valid


def generate_checksums(model_dir: Path, artifacts: Optional[list[str]] = None) -> None:
    if artifacts is None:
        artifacts = [
            f.name for f in model_dir.iterdir()
            if f.is_file() and not f.name.startswith(".") and f.name != CHECKSUM_FILENAME
        ]

    checksums_path = model_dir / CHECKSUM_FILENAME
    with open(checksums_path, "w") as f:
        for name in sorted(artifacts):
            file_path = model_dir / name
            if file_path.exists():
                digest = compute_sha256(file_path)
                f.write(f"{digest}  {name}\n")
    logger.info("integrity.generated_checksums dir=%s files=%d", model_dir, len(artifacts))
