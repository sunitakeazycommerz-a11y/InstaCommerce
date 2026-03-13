# ADR-010: Per-Service Token Rollout

## Status
Accepted

## Date
2026-03-13

## Context
All 28 services share a single INTERNAL_SERVICE_TOKEN. A compromise of any
service grants full impersonation capability across the entire platform.
ADR-006 identified this as a critical security gap.

## Decision
Introduce per-service tokens alongside the existing shared token. Each
receiving service maintains a map of {callerServiceName -> expectedToken}
via the INTERNAL_SERVICE_ALLOWED_CALLERS configuration (JSON map for Go/Python,
SpEL map for Java).

During migration, services accept both per-service tokens (checked first) and
the shared token (fallback). Once all callers are migrated, the shared token
fallback will be removed.

The insecure dev fallback token (dev-internal-token-change-in-prod) is removed
from all service client configurations to prevent accidental use in production.

## Consequences
- Per-service secrets must be provisioned in GCP Secret Manager
- Helm values must be updated per-environment to inject caller-specific tokens
- Token rotation affects only a single caller-receiver pair, not the entire fleet
- Shared token remains as fallback until Wave 28 full migration
