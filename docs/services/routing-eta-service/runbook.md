# Routing & ETA Service - Operational Runbook

## Service Metadata
- **Canonical Port:** `8094`
- **Module:** `services/routing-eta-service`
- **Readiness Probe:** `http://localhost:8094/actuator/health/readiness`
- **Liveness Probe:** `http://localhost:8094/actuator/health/liveness`
- **Local Startup:** `./gradlew :services:routing-eta-service:bootRun --args='--server.port=8094'`

---

## Operational Procedures

### 1. Delivery Tracking & Real-Time ETA Checks
```bash
# Check ETA prediction for an active delivery
curl -s http://localhost:8094/deliveries/<DELIVERY_ID>/eta | jq .

# Inspect tracking history
curl -s http://localhost:8094/deliveries/<DELIVERY_ID>/tracking | jq .
```

### 2. OSRM Routing Engine Fallbacks
**Symptom:** Upstream OSRM / Google Maps API rate limits or network errors.
**Resolution:**
The service automatically falls back to Haversine distance matrix calculations with configured congestion multipliers.
```bash
# Verify fallback status in logs
kubectl logs -n instacommerce deploy/routing-eta-service --tail=100 | grep -i "fallback\|haversine"
```

### 3. Traffic Congestion & Weather Barriers
```bash
# Add temporary road barrier or weather alert
curl -X POST -H "Content-Type: application/json" \
  -d '{"name": "Monsoon Flooding", "reason": "Severe waterlogging on bridge", "minLat": 12.9700, "minLng": 77.5900, "maxLat": 12.9800, "maxLng": 77.6000, "detourPenaltyKm": 3.5, "detourPenaltyMinutes": 12}' \
  http://localhost:8094/admin/routing/barriers
```
