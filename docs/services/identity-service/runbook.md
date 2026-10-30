# Identity Service - Operational Runbook

## Service Metadata
- **Canonical Port:** `8081`
- **Module:** `services/identity-service`
- **Readiness Probe:** `http://localhost:8081/actuator/health/readiness`
- **Liveness Probe:** `http://localhost:8081/actuator/health/liveness`
- **JWKS Endpoint:** `http://localhost:8081/.well-known/jwks.json`
- **Local Startup:** `./gradlew :services:identity-service:bootRun --args='--server.port=8081'`

---

## Operational Procedures

### 1. JWKS Health & Key Verification
```bash
# Verify public key set availability
curl -s http://localhost:8081/.well-known/jwks.json | jq .
# Must return keys array with valid RSA 'kid' and 'n' / 'e' parameters
```

### 2. JWKS Key Rotation Triage
When rotating RSA key pairs:
1. Stage the new key in Google Secret Manager / Kubernetes Secrets (`JWT_KEY_ROTATION_NEXT_PRIVATE_KEY`).
2. Reload JWKS in identity-service without downtime. Both current and previous keys are published in the JWKS set.
3. Allow 15 minutes for all downstream microservices to refresh their JWKS cache.
4. Promote the new key to active signing.

### 3. Account Lockout Triage
```bash
# Unlock locked user account
curl -X POST -H "Authorization: Bearer <ADMIN_TOKEN>" \
  http://localhost:8081/admin/users/<USER_ID>/unlock
```
