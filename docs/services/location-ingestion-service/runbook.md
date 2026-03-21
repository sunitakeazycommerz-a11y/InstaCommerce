# Location Ingestion Service Runbook

## Pre-Deployment Verification Checklist

- [ ] Verify location-db (PostgreSQL/TimescaleDB for time-series)
  ```bash
  kubectl exec -n data deploy/location-ingestion-service -- \
    curl -s http://localhost:8113/actuator/health/db | jq '.components.db.status'
  ```

- [ ] Check Redis GEO connectivity (location indexing)
  ```bash
  kubectl exec -n data deploy/location-ingestion-service -- \
    curl -s http://localhost:8113/actuator/health | jq '.components.redis'
  ```

- [ ] Verify Kafka connectivity (rider.location topic)
  ```bash
  kubectl exec -n data deploy/location-ingestion-service -- \
    curl -s http://localhost:8113/actuator/health | jq '.components.kafka'
  ```

---

## Deployment Procedures

### Pre-Deployment Baseline

```bash
# Consumer lag (should be <5 seconds for real-time tracking)
kubectl exec -n data deploy/location-ingestion-service -- \
  curl -s http://localhost:8113/metrics/kafka-consumer-lag | jq '.value'

# Location ingestion rate (events/sec)
kubectl exec -n data deploy/location-ingestion-service -- \
  curl -s http://localhost:8113/metrics/ingestion-rate-events-per-sec | jq '.value'
```

### Blue-Green Deployment

```bash
helm upgrade location-ingestion-service deploy/helm/location-ingestion-service -n data
kubectl rollout status deploy/location-ingestion-service -n data --timeout=10m

# Verify consumer lag recovered
kubectl exec -n data deploy/location-ingestion-service -- \
  curl -s http://localhost:8113/metrics/kafka-consumer-lag | jq '.value'
```

---

## Incident Response Procedures

### P0 - Location Tracking Down

**Symptom**: Rider locations not updating, real-time dispatch not working

**Diagnosis**:
```bash
kubectl get pods -n data -l app=location-ingestion-service
kubectl logs -n data deploy/location-ingestion-service --tail=100
kubectl exec -n data deploy/location-ingestion-service -- \
  curl -s http://localhost:8113/metrics/kafka-consumer-lag | jq '.value'
```

**Resolution**:
1. Restart: `kubectl rollout restart deploy/location-ingestion-service -n data`
2. Scale up: `kubectl scale deploy location-ingestion-service -n data --replicas=5`
3. Check Kafka connectivity

### P1 - Location Lag High (>30 seconds)

**Diagnosis**:
```bash
# Check lag trend
kubectl exec -n data deploy/location-ingestion-service -- \
  curl -s http://localhost:8113/metrics/kafka-consumer-lag | jq '.value'

# Check ingestion rate
kubectl exec -n data deploy/location-ingestion-service -- \
  curl -s http://localhost:8113/metrics/ingestion-rate-events-per-sec | jq '.value'
```

**Resolution**:
1. Scale up: `kubectl scale deploy location-ingestion-service -n data --replicas=5`
2. Increase consumer concurrency

---

## Monitoring & Alerting

### Key Metrics

```
# Location ingestion lag
location_ingestion_lag_seconds

# Ingestion rate
location_ingestion_rate_events_per_sec

# GEO index freshness
location_geo_index_age_seconds
```

---

## On-Call Handoff

- [ ] Consumer lag? `kubectl exec -n data deploy/location-ingestion-service -- curl -s http://localhost:8113/metrics/kafka-consumer-lag`

---

## Related Documentation

- **Deployment**: `/deploy/helm/location-ingestion-service/`
