# ADR-007: Python Service CI Standard

## Status

Accepted

## Date

2026-03-13

## Context

The InstaCommerce platform includes two Python-based AI services:
`ai-orchestrator-service` and `ai-inference-service`. Both services were deployed to
production with zero CI validation. Specifically:

1. **No linting**: Neither service had any static analysis or code style enforcement.
   Syntax errors and import issues could reach production undetected.

2. **No testing**: No test suites existed, meaning broken endpoints, missing
   dependencies, and runtime import failures were only discovered after deployment.

3. **No Docker image scanning**: Container images were built and pushed without
   vulnerability scanning, leaving known CVEs in base images and Python dependencies
   undetected.

4. **No path-filtered CI triggers**: Changes to Python services did not trigger any
   CI pipeline, as the existing `ci.yml` workflow only covered Java/Gradle services.

This gap created a class of services that bypassed all quality and security gates
enforced on the rest of the platform.

## Decision

**All Python services MUST have CI validation equivalent to the Java service standard.**

Implementation requirements:

1. All Python services MUST have a `python-build-test` CI job in the repository's
   `ci.yml` workflow. This job MUST execute the following steps in order:
   - `pytest` to run the test suite.
   - `docker build` to verify the Docker image builds successfully.
   - `trivy image` scan to detect known vulnerabilities in the built image.

2. Path filters MUST be configured in `ci.yml` for each Python service directory so
   that the `python-build-test` job runs only when files under that service's path
   are modified. For example, changes under `ai-orchestrator-service/` trigger CI for
   that service only.

3. Minimum test requirement: every Python service MUST include at least a health
   endpoint smoke test that starts the FastAPI application and verifies the `/health`
   endpoint returns HTTP 200.

4. Dependencies MUST be managed via `requirements.txt` with fully pinned versions
   (e.g., `fastapi==0.115.0`, not `fastapi>=0.115`). This ensures reproducible builds
   and prevents surprise breakages from transitive dependency updates.

5. The Python version used in CI MUST match the version specified in the service's
   `Dockerfile`. The current standard is Python 3.14.

## Consequences

### Positive

- Catches import errors, broken endpoints, and missing dependencies before code
  reaches production.
- Trivy scanning detects known security vulnerabilities in base images and Python
  packages, bringing Python services to parity with the Java service security posture.
- Path-filtered triggers ensure Python CI runs only when relevant, avoiding
  unnecessary pipeline execution on unrelated changes.

### Negative

- Adds CI execution time for Python service changes. The `docker build` and `trivy`
  steps are the most time-consuming, adding approximately 2-4 minutes per run.

### Risks

- Some FastAPI dependencies may have import-time side effects (e.g., model loading,
  GPU initialization) that require test-time mocking or environment variable overrides
  to prevent failures in CI where those resources are unavailable.
- Pinned dependency versions require periodic manual updates to pick up security
  patches; a future Dependabot or Renovate configuration should automate this.
