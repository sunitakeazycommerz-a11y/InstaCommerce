# Audit Trail Service - Operational Runbook

## Service Metadata
- **Canonical Port:** `8096`
- **Module:** `services/audit-trail-service`
- **Readiness Probe:** `http://localhost:8096/actuator/health/readiness`
- **Liveness Probe:** `http://localhost:8096/actuator/health/liveness`
- **Metrics Endpoint:** `http://localhost:8096/actuator/prometheus`
- **Local Startup:** `./gradlew :services:audit-trail-service:bootRun --args='--server.port=8096'`
- **Kafka Topics Consumed:** 20 canonical domain topics (`TopicNames.*`)

---

## Operational Procedures

### 1. Ingestion Verification & Consumer Lag
```bash
# Check service readiness
curl -s http://localhost:8096/actuator/health/readiness | jq .

# Verify event ingestion rate
curl -s http://localhost:8096/actuator/prometheus | grep audit_events_ingested_total
```

### 2. Tamper-Evident SHA-256 Hash Chain Verification
Verify cryptographic chain integrity across the append-only `audit_events` table:
```bash
# Execute chain integrity check
curl -s http://localhost:8096/admin/audit/integrity/verify | jq .
# Expected output: {"status": "VALID", "verifiedEvents": <count>, "lastChainHash": "<sha256>"}
```

### 3. GCS Partition Archival & Cold Storage
The service automatically archives historical event partitions to Google Cloud Storage.
- **Config:** `AUDIT_ARCHIVE_BUCKET_NAME`, `AUDIT_ARCHIVE_RETENTION_DAYS=2555` (7-year compliance).
- **Manual Verification:**
  ```bash
  # Check archival status
  kubectl logs -n instacommerce deploy/audit-trail-service --tail=50 | grep -i "archive"
  ```

### 4. Dead Letter Topic (DLT) Replay
Failed consumer records are diverted to `{topic}.DLT`.
```bash
# Inspect DLT consumer group lag
kafka-consumer-groups.sh --bootstrap-server kafka:9092 --group audit-trail-service-group --describe
```
