# ADR-006: Internal Service Authentication Hardening

## Status

Accepted

## Date

2026-03-13

## Context

All Java services in the InstaCommerce platform shared a single `INTERNAL_SERVICE_TOKEN`
environment variable for service-to-service authentication. This mechanism had several
security weaknesses:

1. **Timing-vulnerable comparison**: 6 of 7 services used `String.equals()` to verify
   the token, which is susceptible to timing side-channel attacks that can leak token
   bytes incrementally.

2. **Overprivileged role grants**: 6 of 7 services granted `ROLE_ADMIN` to any caller
   presenting a valid internal token. This meant a compromised token gave full
   administrative access across the entire platform, not just inter-service
   communication privileges.

3. **Hardcoded default tokens**: Several services included a default token value in
   `application.yml` (e.g., `internal-service-token-default`), allowing the service
   to start without an explicit environment variable and silently operate with an
   insecure, publicly visible credential.

4. **Single shared secret**: All services shared the same token value, meaning
   compromise of any single service's token exposed every service simultaneously.

## Decision

**All services MUST harden their internal authentication filter with the following
requirements.**

1. All services MUST use `MessageDigest.isEqual()` (or equivalent constant-time
   comparison) when verifying the `INTERNAL_SERVICE_TOKEN`. Direct `String.equals()`
   comparison is prohibited.

2. A valid internal service token MUST grant only `ROLE_INTERNAL_SERVICE`, never
   `ROLE_ADMIN`. The `ROLE_INTERNAL_SERVICE` authority is sufficient for all
   legitimate inter-service API calls and must be the only authority assigned by the
   internal authentication filter.

3. No hardcoded default token values are permitted in `application.yml` or any
   configuration file checked into version control. The `INTERNAL_SERVICE_TOKEN`
   environment variable MUST be required at startup; if absent, the service MUST
   fail to start with a clear error message.

4. Migration path toward zero-trust workload identity:
   - **Current (Wave 25)**: Shared `INTERNAL_SERVICE_TOKEN` with hardened comparison
     and reduced privileges.
   - **Wave 28**: Per-service tokens issued by a secrets manager, enabling revocation
     of individual service credentials without rotating all tokens.
   - **Wave 30+**: SPIFFE/SPIRE workload identity with mTLS, eliminating static tokens
     entirely.

## Consequences

### Positive

- Eliminates timing side-channel attacks on token verification across all services.
- Reduces the blast radius of a compromised token: attackers gain only
  `ROLE_INTERNAL_SERVICE` (limited inter-service access) rather than `ROLE_ADMIN`
  (full platform control).
- Removing hardcoded defaults ensures tokens are always explicitly provisioned,
  preventing accidental deployment with insecure credentials.

### Negative

- All deployment environments (dev, staging, production) must have the
  `INTERNAL_SERVICE_TOKEN` environment variable explicitly set. Services will refuse
  to start if the variable is missing, which may disrupt local development workflows
  that previously relied on the hardcoded default.

### Risks

- Any code that checks `hasRole("ADMIN")` to authorize internal service callers will
  break after the role is changed to `ROLE_INTERNAL_SERVICE`. All `@PreAuthorize`
  annotations and `SecurityConfig` rules across all services must be audited and
  updated before this change is deployed.
- During the migration window, services that have been updated and services that have
  not will coexist. The shared token value remains valid for both, but role-based
  authorization differences may cause unexpected 403 responses if callers depend on
  `ROLE_ADMIN`.
