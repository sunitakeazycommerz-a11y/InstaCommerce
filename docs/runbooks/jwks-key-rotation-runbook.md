# JWKS Key Rotation & Overlapping Grace Period Runbook

## 1. Overview & Objectives

In InstaCommerce, `identity-service` acts as the OpenID Connect / OAuth2 authorization server, issuing RS256-signed JSON Web Tokens (JWT) for authenticated users and internal workloads. The public keys for token signature verification are published at `GET /.well-known/jwks.json`.

Cryptographic hygiene mandates periodic rotation of RSA signing keypairs (every 90 days) and immediate rotation in the event of credential leakage. Because tokens carry active lifetimes (access tokens 15m, refresh sessions up to 7 days), sudden key replacement causes immediate authorization failures across the entire fleet.

This runbook specifies the zero-downtime **7-Day Overlapping Dual-Key Rotation Procedure** supported by `JwtKeyLoader` and `JwksController`.

---

## 2. Cryptographic Architecture

```
                                      [ Phase 1: Dual-Key Grace Period ]
                                       
                                        +---------------------------------------+
                                        |  identity-service /.well-known/jwks   |
                                        |  - Key N   (active, signing + verify) |
                                        |  - Key N-1 (grace, verification only) |
                                        +---------------------------------------+
                                                     |                     |
                        +----------------------------+                     +---------------------------+
                        |                                                                              |
                        v                                                                              v
        +-------------------------------+                                              +-------------------------------+
        |  Gateway & Edge Services      |                                              |  Gateway & Edge Services      |
        |  Verifying new tokens (Key N) |                                              |  Verifying old tokens (Key N-1|
        +-------------------------------+                                              +-------------------------------+
```

- **Active Key (`N`)**: Signs newly minted access tokens (`header.kid = activeKeyId`).
- **Previous Keys (`N-1`)**: Listed under `identity.jwt.previous-public-keys`. Published in JWKS and resolved dynamically via JJWT's `keyLocator` on incoming requests.
- **Grace Window**: Minimum 7 days, matching the maximum refresh token lifetime and caching TTLs of downstream consumers.

---

## 3. Step-by-Step Rotation Playbook

### Phase 1: Generate New Keypair (Key N)

Generate a new 4096-bit RSA keypair in PKCS#8 PEM format:

```bash
# 1. Generate private key
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 -out identity-private-key-v2.pem

# 2. Extract public key in X.509 SubjectPublicKeyInfo format
openssl rsa -pubout -in identity-private-key-v2.pem -out identity-public-key-v2.pem

# 3. Verify key modulus and format
openssl rsa -in identity-private-key-v2.pem -check -noout
openssl rsa -pubin -in identity-public-key-v2.pem -text -noout
```

### Phase 2: Update Secret Manager / Kubernetes Secrets

1. Store the new private key into Secret Manager:
   ```bash
   gcloud secrets versions add identity-jwt-private-key --data-file=identity-private-key-v2.pem
   ```
2. Retrieve the existing active public key (`Key N-1`) from Secret Manager / ConfigMap.
3. Update Helm values (`values-prod.yaml` / `values-staging.yaml`):
   ```yaml
   identity-service:
     jwt:
       publicKey: "<contents-of-identity-public-key-v2.pem>"
       privateKey: "<contents-of-identity-private-key-v2.pem>"
       previousPublicKeys:
         - "<contents-of-identity-public-key-v1.pem>"
   ```

### Phase 3: Rolling Deployment & Smoke Testing

1. Trigger rolling restart of `identity-service`:
   ```bash
   kubectl rollout restart deployment/identity-service -n instacommerce
   kubectl rollout status deployment/identity-service -n instacommerce
   ```
2. Verify `GET /.well-known/jwks.json` returns both keys:
   ```bash
   curl -s http://identity-service:8080/.well-known/jwks.json | jq '.keys[] | {kid, alg, kty}'
   ```
   **Expected Output:**
   Two distinct JSON Web Key objects, both with `alg: "RS256"` and `kty: "RSA"`.
3. Verify dual-verification smoke test:
   - Login with test user: Verify token signs with new `kid`.
   - Replay token issued prior to deployment: Verify authorization continues without `401 Unauthorized`.

### Phase 4: Overlapping 7-Day Grace Window Monitoring

During the 7-day window:
- Monitor Prometheus metrics:
  - `identity_auth_failures_total`: Should remain at baseline (< 0.01%).
  - `http_server_requests_seconds_count{status="401"}`: No anomalous spikes.
- Downstream services caching JWKS (e.g., `admin-gateway`, `mobile-bff`, Envoy proxy) refresh keys according to cache headers.

### Phase 5: Decommission Key N-1

Once 7 days have elapsed:
1. Remove `Key N-1` from `previousPublicKeys` in Helm values:
   ```yaml
   identity-service:
     jwt:
       publicKey: "<contents-of-identity-public-key-v2.pem>"
       privateKey: "<contents-of-identity-private-key-v2.pem>"
       previousPublicKeys: []
   ```
2. Apply changes and rollout update.
3. Verify `GET /.well-known/jwks.json` now returns only `Key N`.
4. Securely archive / destroy deprecated private key file `identity-private-key-v1.pem`.

---

## 4. Emergency Revocation (Key Compromise)

If the active private key is suspected of being compromised:
1. **Immediate Break-Glass**: Do NOT use overlapping grace period.
2. Generate fresh keypair `Key N+1`.
3. Clear `previousPublicKeys` to immediately invalidate all compromised tokens.
4. Deploy immediately to `identity-service`.
5. Force cache invalidation on gateway services (`admin-gateway`, `mobile-bff`).
6. Evict all active refresh tokens in Redis / PostgreSQL:
   ```sql
   UPDATE refresh_tokens SET revoked_at = NOW() WHERE revoked_at IS NULL;
   ```
7. Broadcast mandatory re-login alert to clients.

---

## 5. Alerts & Runbook Automation

Prometheus alerting rule for key age:

```yaml
- alert: IdentitySigningKeyAging
  expr: (time() - identity_jwt_key_created_timestamp_seconds) > 75 * 86400
  for: 24h
  labels:
    severity: warning
    team: secops
  annotations:
    summary: "identity-service JWT signing key is over 75 days old"
    description: "Plan and execute JWKS rotation according to docs/runbooks/jwks-key-rotation-runbook.md."
```
