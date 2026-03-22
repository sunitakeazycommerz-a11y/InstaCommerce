# Location Ingestion Service

## Overview

The location-ingestion-service is a high-performance GPS event ingestion and geospatial processing engine. It collects location data from delivery riders, validates events, applies geographic filtering rules, maintains a Redis-based geospatial index, and publishes location updates for real-time tracking, delivery routing, and warehouse fulfillment optimization.

**Service Ownership**: Fulfillment Team - Location Platform
**Language**: Go 1.22
**Default Port**: 8086
**Status**: Tier 2 Critical (Real-time rider tracking)

## SLOs & Availability

- **Availability SLO**: 99.5% (216 minutes downtime/month)
- **P99 Latency**: < 100ms ingestion (event -> Redis/Kafka)
- **Error Rate**: < 0.5% (malformed/invalid GPS)
- **Max Throughput**: 10,000 events/second (1000+ riders * 10 events/sec each)

## Key Responsibilities

1. **GPS Event Ingestion**: Accept WebSocket + REST location updates from mobile rider apps
2. **Location Validation**: Validate GPS coordinates (lat/lon range), timestamp freshness, accuracy
3. **Geospatial Indexing**: Maintain Redis GEO index for fast nearest-warehouse lookups
4. **Geographic Filtering**: Apply geofence rules; filter out-of-service-area locations
5. **Event Publishing**: Emit validated events to Kafka for downstream routing/tracking services
6. **Batching & Throttling**: Batch events to reduce downstream load; rate-limit per rider

## Deployment

### GKE Deployment
- **Namespace**: fulfillment
- **Replicas**: 2 (HA, stateless)
- **CPU Request/Limit**: 500m / 1000m
- **Memory Request/Limit**: 512Mi / 1024Mi

### Cluster Configuration
- **Ingress**: Internal + External (WebSocket from rider app + admin dashboards)
- **NetworkPolicy**: Allow from rider-fleet-service, routing-eta-service
- **Service Account**: location-ingestion-service
- **Load Balancer**: GKE Service with session affinity (WebSocket sticky sessions)

## Geospatial Architecture

```
┌─────────────────────────────────────────────────────────────┐
│ Rider Mobile App (GPS every 10-30 seconds)                  │
└────────────────────┬────────────────────────────────────────┘
                     │
        1. WebSocket or REST: {"lat":37.7749,"lon":-122.4194}
                     │
    ┌────────────────▼────────────────────────────┐
    │ Location-Ingestion-Service                  │
    │ ├─ Validate GPS (lat/lon range, accuracy)  │
    │ ├─ GeoHash encoding                        │
    │ ├─ Geofence check (service area)           │
    │ ├─ Redis GEO index: GEOADD riders ...      │
    │ ├─ Batch events (50 events / 100ms)        │
    │ └─ Publish to Kafka                        │
    └────────────────┬────────────────────────────┘
                     │
    ┌────────────────┴─────────────────────┐
    │                                      │
    ▼                                      ▼
┌──────────────────┐         ┌─────────────────────┐
│ Redis GEO Index  │         │ Kafka Topic:        │
│ Riders (lat,lon) │         │ location-updates    │
│ Warehouses (geo) │         │ (10k events/sec)    │
└──────────────────┘         └─────────────────────┘
    │
    │ GEORADIUS queries
    │
    ▼
┌──────────────────────────────────────┐
│ Downstream Services                  │
├─ Routing-ETA-Service (nearest)      │
├─ Dispatch-Optimizer (assignment)    │
├─ Warehouse-Service (fulfillment)    │
└──────────────────────────────────────┘
```

### Event Validation & Filtering

1. **GPS Validation**: Check lat ∈ [-90,90], lon ∈ [-180,180], accuracy ≤ 100m
2. **Timestamp Check**: Event must be recent (< 5 seconds old); reject stale updates
3. **Geofence Lookup**: Check if location within service areas using Redis GEO
4. **Deduplication**: Filter duplicate locations (same rider, same lat/lon within 10m)
5. **Rate Limiting**: Max 100 events/sec per rider (circuit breaker if exceeded)

## Integrations

### Kafka Topics
| Topic | Producer/Consumer | Partitions | Purpose | Retention |
|-------|------------------|-----------|---------|-----------|
| location-updates | Producer | 20 | Raw rider locations | 24 hours |
| rider-assignments | Consumer | 10 | Assignment events (for context) | 24 hours |

### Redis Collections
- **GEO Index**: `riders:{riderId}` (lat, lon, score=timestamp)
- **Service Areas**: `warehouses:geo` (warehouse polygons as GEO points)
- **Geofence Cache**: `geofence:{geohash}` (binary filter for area membership)
- **Rate Limits**: `rider-rate:{riderId}` (token bucket, 100 events/sec per rider)

### REST Dependencies
| Service | Endpoint | Purpose | Timeout |
|---------|----------|---------|---------|
| warehouse-service | http://warehouse-service:8088/warehouses/geo | Geofence polygons | 5s (cached 5 min) |

## Endpoints

### Public (Unauthenticated - API Key Required)
- `GET /health` - Liveness probe
- `GET /metrics` - Prometheus metrics (Prometheus format)

### WebSocket (Requires API Key)
- `WS /v1/location/stream` - Real-time location updates (persistent connection)

### Location API (Requires JWT)
- `POST /v1/location/update` - Single location update (fallback for non-WebSocket clients)
- `GET /v1/location/rider/{riderId}` - Get rider's current location
- `GET /v1/location/nearby?lat=37.7749&lon=-122.4194&radiusKm=5` - Find riders within radius
- `GET /v1/location/stats` - Current ingestion metrics

### Example Requests

```bash
# WebSocket location streaming (from rider app)
wscat -c 'ws://location-ingestion:8086/v1/location/stream?apiKey=rider-key-123'
# Then send: {"riderId":"rider-001","lat":37.7749,"lon":-122.4194,"accuracy":8,"timestamp":"2024-01-01T12:00:00Z"}

# REST location update
curl -X POST 'http://location-ingestion:8086/v1/location/update' \
  -H 'Authorization: Bearer <jwt_token>' \
  -H 'Content-Type: application/json' \
  -d '{"riderId":"rider-001","lat":37.7749,"lon":-122.4194,"accuracy":8}'

# Find nearby riders
curl -X GET 'http://location-ingestion:8086/v1/location/nearby?lat=37.7749&lon=-122.4194&radiusKm=2' \
  -H 'Authorization: Bearer <jwt_token>'

# Response:
# {
#   "riders": [
#     {"riderId":"rider-001","lat":37.7755,"lon":-122.4188,"distance_km":0.45},
#     {"riderId":"rider-002","lat":37.7701,"lon":-122.4210,"distance_km":0.72}
#   ]
# }

# Get rider current location
curl -X GET 'http://location-ingestion:8086/v1/location/rider/rider-001' \
  -H 'Authorization: Bearer <jwt_token>'
```

## Configuration

### Environment Variables
```env
SERVER_PORT=8086
KAFKA_BROKERS=kafka1:9092,kafka2:9092,kafka3:9092
KAFKA_TOPIC=location-updates
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_PASSWORD=<redis_password>
WAREHOUSE_SERVICE_URL=http://warehouse-service:8088
GEOFENCE_CACHE_TTL_MINUTES=5
RIDER_RATE_LIMIT_EVENTS_PER_SEC=100
GPS_ACCURACY_THRESHOLD_METERS=100
LOCATION_STALENESS_THRESHOLD_SECONDS=5
BATCH_SIZE=50
BATCH_TIMEOUT_MS=100
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
LOG_LEVEL=info
```

### config.yaml
```yaml
server:
  port: ${SERVER_PORT:8086}
  websocket-max-connections: 5000

kafka:
  brokers: ${KAFKA_BROKERS}
  topic: ${KAFKA_TOPIC:location-updates}
  partitions: 20
  producer-timeout-ms: 5000

redis:
  host: ${REDIS_HOST:redis}
  port: ${REDIS_PORT:6379}
  password: ${REDIS_PASSWORD}
  pool-size: 50

geofencing:
  cache-ttl-minutes: ${GEOFENCE_CACHE_TTL_MINUTES:5}
  warehouse-service-url: ${WAREHOUSE_SERVICE_URL}
  service-area-buffer-meters: 500

gps-validation:
  accuracy-threshold-meters: ${GPS_ACCURACY_THRESHOLD_METERS:100}
  staleness-threshold-seconds: ${LOCATION_STALENESS_THRESHOLD_SECONDS:5}

rate-limiting:
  events-per-sec-per-rider: ${RIDER_RATE_LIMIT_EVENTS_PER_SEC:100}
  token-bucket-window-ms: 1000

batching:
  batch-size: ${BATCH_SIZE:50}
  batch-timeout-ms: ${BATCH_TIMEOUT_MS:100}

observability:
  otlp-endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT}
  log-level: ${LOG_LEVEL:info}
```

## Monitoring & Alerts

### Key Metrics
- `location_events_received_total` (counter) - Events received from riders
- `location_events_valid_total` (counter) - Validated events
- `location_events_dropped_total` (counter) - Invalid/dropped events (by reason: stale, out-of-bounds, etc.)
- `location_geofence_hits_total` (counter) - In-service-area events
- `location_geofence_misses_total` (counter) - Out-of-service-area events
- `location_ingestion_latency_ms` (histogram) - Time from event receipt to Kafka publish
- `location_kafka_publish_errors_total` (counter) - Failed Kafka publishes
- `location_redis_geo_size_bytes` (gauge) - Redis GEO index memory usage
- `location_active_riders_gauge` (gauge) - Riders with updates in past 5 minutes

### Alerting Rules
- `location_events_received_total == 0 for 5min` - No location data flowing (app/rider connection issue)
- `location_events_dropped_rate > 5%` - High drop rate (may indicate GPS accuracy issues)
- `location_ingestion_latency_p99 > 500ms` - Performance degradation (Kafka/Redis slow)
- `location_kafka_publish_errors_total > 100/min` - Kafka connection issue
- `location_redis_geo_memory_usage > 2GB` - Redis memory pressure (clean old entries)
- `location_geofence_miss_rate > 10%` - High out-of-area events (riders leaving service zones)

### Logging
- **ERROR**: GPS validation failures (out-of-bounds, stale), Kafka publish errors
- **WARN**: Rate limit exceeded for rider, geofence cache miss, Redis connection issues
- **INFO**: Active rider count, batch publish stats (every 5 minutes)

## Security Considerations

### Threat Mitigations
1. **API Key Authentication**: WebSocket clients require rider API key (issued by rider-fleet-service)
2. **GPS Spoofing Detection**: Rate-limit teleportation (>200 km/hour → suspicious)
3. **Geofence Enforcement**: Reject locations outside service areas (prevent rider app manipulation)
4. **Event Deduplication**: Prevent replay attacks by tracking idempotency keys
5. **Audit Logging**: All location updates logged with rider ID, timestamp, accuracy

### Known Risks
- **GPS Spoofing**: Malicious app could emit false coordinates (mitigated by impossible velocity checks)
- **Rate Limit Bypass**: High-frequency updates could overwhelm downstream services (100 events/sec per rider limit)
- **Redis GEO Pollution**: Compromised upstream service could corrupt geofence cache (impacts routing)
- **Privacy Leakage**: Location data visible to entire fleet (no rider privacy controls yet)

## Troubleshooting

### No Location Events Flowing
1. **Check rider connectivity**: Verify riders can connect to WebSocket endpoint (test with `wscat`)
2. **Verify API keys**: Confirm rider app has valid API key issued by rider-fleet-service
3. **Check Kafka**: `kafka-console-consumer --topic location-updates --from-beginning` verify events appear
4. **Review logs**: Look for connection errors, authentication failures (ERROR level)

### High Event Drop Rate
1. **Check GPS accuracy**: Validate that rider devices report accuracy ≤ 100m
2. **Review geofence logic**: Confirm geofence cache is fresh (check `geofence_cache_miss_rate`)
3. **Verify warehouse service**: Ensure warehouse-service:8088 is reachable and responsive
4. **Monitor rate limits**: Check if `rider_rate_limit_exceeded_total` counter increasing

### Redis GEO Index Growing Too Large
1. **Check active riders**: Monitor `location_active_riders_gauge` (should reflect actual rider count)
2. **Review TTL policy**: Verify Redis expires old rider keys (check Redis EXPIREAT policy)
3. **Clean stale entries**: Run Redis command `ZREMRANGEBYRANK riders 0 -2000` to prune old riders
4. **Increase Redis capacity**: If growth continues, scale Redis cluster

## Related Documentation

- **Runbook**: location-ingestion-service/runbook.md
- **Geofence Design**: Service area configuration and polygon storage
- **Router Integration**: Real-time location consumption by routing-eta-service
- **Performance Tuning**: WebSocket optimization guide
