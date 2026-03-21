# Tier 5 Data/ML/AI/Async Services - Diagram Index

**Status**: All 49 diagrams (7 per service × 7 services) created and verified.

**Total Diagram Files**: 49
**Location**: `/docs/services/{service-name}/diagrams/`
**Format**: Mermaid markdown (.md)
**Quality**: Production-grade with code verification

---

## Tier 5 Services Overview

Tier 5 comprises 7 asynchronous, data-driven, ML, and event processing services that handle the real-time analytics, fraud prevention, audit compliance, and AI workloads of the platform.

### Service List

| # | Service | Runtime | Port | Purpose |
|---|---------|---------|------|---------|
| 1 | **ai-inference-service** | Python 3.14 / FastAPI | 8101 | Online model serving (7 models: ETA, Fraud, Ranking, Demand, Personalization, CLV, Dynamic Pricing) |
| 2 | **ai-orchestrator-service** | Python 3.14 / FastAPI + LangGraph | 8100 | Agentic LLM orchestration with read-only tool registry |
| 3 | **cdc-consumer-service** | Go 1.24 | 8104 | Debezium CDC → BigQuery sink with dead-letter queue |
| 4 | **location-ingestion-service** | Go 1.24 | 8105 | High-throughput GPS ingestion (HTTP + WebSocket) → Redis + Kafka |
| 5 | **stream-processor-service** | Go 1.24 | 8108 | Real-time Kafka stream aggregations for operational metrics |
| 6 | **fraud-detection-service** | Java 21 / Spring Boot 3 | 8090 | Multi-stage fraud scoring with 5 rule types + velocity tracking |
| 7 | **audit-trail-service** | Java 21 / Spring Boot 3 | 8092 | Immutable append-only audit log (14 domain topics, monthly partitions) |

---

## Diagram Set 1: AI Inference Service

**Language**: Python / FastAPI
**Models**: 7 (ETA, Fraud, Ranking, Demand, Personalization, CLV, Dynamic Pricing)
**Key Pattern**: Online prediction plane with caching, fallbacks, and shadow mode

| Diagram | File | Focus |
|---------|------|-------|
| **1. HLD** | `1-hld.md` | Client callers → API → Registry → Inference Engine → Cache/FeatureStore/Fallback → Prometheus |
| **2. LLD** | `2-lld.md` | Settings, ModelRegistry, InferenceCache, FeatureStoreClient, ShadowRunner, MetricsCollector |
| **3. Flowchart** | `3-flowchart.md` | Request parsing → cache check → model resolution → feature fetch → inference → fallback → shadow → response |
| **4. Sequence** | `4-sequence.md` | Client → API → Cache → Registry → Features → Engine → Prometheus |
| **5. State Machine** | `5-state-machine.md` | Unregistered → Registered → Active → Inference → Cached → Inactive |
| **6. ER Diagram** | `6-er.md` | SETTINGS, MODEL_ENTRY, MODEL_VERSION, MODEL_WEIGHTS, CACHE_ENTRY, FEATURE_VALUE, MODEL_RESULT |
| **7. E2E Flow** | `7-e2e.md` | Complete request latency (cache hit <2ms, miss <100ms p99) with fallbacks and metrics |

**Key Characteristics**:
- 7 ML models with versioning
- LRU in-memory cache (default 1000 entries, 300s TTL)
- Feature store integration (Redis online, BigQuery offline, or request-only)
- Shadow mode for A/B testing
- Fallback rule-based scoring

---

## Diagram Set 2: AI Orchestrator Service

**Language**: Python / FastAPI + LangGraph
**Pattern**: Agentic orchestration with state machine and guardrails

| Diagram | File | Focus |
|---------|------|-------|
| **1. HLD** | `1-hld.md` | BFF → API → Intent Classifier → LangGraph → Tools (8 read-only) → Domain Services |
| **2. LLD** | `2-lld.md` | FastAPIApp, IntentClassifier, Guardrails, LangGraphState, ToolRegistry, CircuitBreaker, CheckpointSaver |
| **3. Flowchart** | `3-flowchart.md` | Rate limit → PII → Injection → Intent → State load → Budget check → Graph execute → Output validate → Checkpoint |
| **4. Sequence** | `4-sequence.md` | Client → API → Guardrails → Intent → State load → Tool execution → Fallback → Output validation → Metrics |
| **5. State Machine** | `5-state-machine.md` | RequestReceived → InputValidation → ClassifiedIntent → StateLoaded → NodeExecution → OutputValidation → ResponseSend |
| **6. ER Diagram** | `6-er.md` | ASSIST_REQUEST, CONVERSATION_STATE, GRAPH_NODE, TOOL_CALL, INTENT, CIRCUIT_BREAKER_STATE, CHECKPOINT |
| **7. E2E Flow** | `7-e2e.md` | Complete orchestration (input gates → classification → budget enforcement → graph loop → safety → finalization) |

**Key Characteristics**:
- LangGraph multi-turn conversation orchestration
- Intent classification (keyword + optional LLM)
- 8 read-only tools with circuit breakers
- Budget enforcement (tool calls + tokens)
- PII redaction and guardrails

---

## Diagram Set 3: CDC Consumer Service

**Language**: Go 1.24
**Pattern**: Debezium CDC → BigQuery sink with deduplication

| Diagram | File | Focus |
|---------|------|-------|
| **1. HLD** | `1-hld.md` | PostgreSQL → Debezium → Kafka CDC Topics → Consumer → BigQuery sink + DLQ |
| **2. LLD** | `2-lld.md` | Config, KafkaReader, MessageBatch, DebeziumEnvelope, BQTransformer, BigQueryInserter, DLQWriter, MetricsCollector |
| **3. Flowchart** | `3-flowchart.md` | Fetch → Parse → Validate envelope → Batch accumulate → Transform to BQ row → Insert → Retry/DLQ → Commit |
| **4. Sequence** | `4-sequence.md` | Kafka → Batch accumulation → Envelope parsing → Row transformation → BigQuery insert → Retry/DLQ → Offset commit |
| **5. State Machine** | `5-state-machine.md` | Fetched → Buffered → Parsed → Transformed → Inserting → BQSuccess/BQFail → OffsetCommit → Committed |
| **6. ER Diagram** | `6-er.md` | KAFKA_MESSAGE, DEBEZIUM_ENVELOPE, MESSAGE_BATCH, BQ_ROW, RETRY_ATTEMPT, DLQ_MESSAGE, METRICS_RECORD |
| **7. E2E Flow** | `7-e2e.md` | Source → Fetch → Parse → Batch → Insert → Retry/DLQ → Commit (at-least-once, dedup within 1 min) |

**Key Characteristics**:
- Debezium envelope parsing (op, before, after, source, ts_ms)
- Batch streaming insert to BigQuery
- Idempotency via insertID = topic-partition-offset
- Exponential backoff with max retries
- DLQ for operator-managed replay

---

## Diagram Set 4: Location Ingestion Service

**Language**: Go 1.24
**Pattern**: High-throughput GPS ingestion → Redis cache + Kafka events

| Diagram | File | Focus |
|---------|------|-------|
| **1. HLD** | `1-hld.md` | Rider GPS → HTTP/WS → Validate → H3 geofence → Redis HSET → Kafka batcher |
| **2. LLD** | `2-lld.md` | HTTPServer, WebSocketHandler, LocationValidator, GeofenceChecker, LatestPositionStore, LocationBatcher, MetricsCollector |
| **3. Flowchart** | `3-flowchart.md` | Parse → Validate bounds → Normalize precision → Geofence check → Redis set → Kafka batch → Metrics |
| **4. Sequence** | `4-sequence.md` | App → HTTP → Validator → Geofence → Redis set → Kafka batch → Metrics → Response |
| **5. State Machine** | `5-state-machine.md` | Received → Validated → Normalized → RedisStore → KafkaBatch → BatchFull → KafkaFlush → Responded |
| **6. ER Diagram** | `6-er.md` | RIDER_APP, LOCATION_REQUEST, VALIDATED_LOCATION, REDIS_ENTRY, KAFKA_MESSAGE, GEOFENCE_ZONE, H3_CELL |
| **7. E2E Flow** | `7-e2e.md` | App sends GPS → Ingestion pipeline → Storage (Redis + Kafka) → Consumers (dispatch, routing, analytics) |

**Key Characteristics**:
- Fire-and-forget semantics
- Coordinate bounds validation
- H3 geofence enrichment (optional)
- Redis latest-position cache with TTL
- Kafka topic for durability

---

## Diagram Set 5: Stream Processor Service

**Language**: Go 1.24
**Pattern**: Real-time Kafka aggregations → Redis metrics + Prometheus

| Diagram | File | Focus |
|---------|------|-------|
| **1. HLD** | `1-hld.md` | 5 Kafka topics (order, payment, rider, inventory, location) → Consumer → Processors → Windows → SLA → Redis + Prom |
| **2. LLD** | `2-lld.md` | Config, KafkaConsumer, EventProcessor (Order/Payment/Rider/Inventory), DeduplicationManager, WindowAggregator, SLAMonitor |
| **3. Flowchart** | `3-flowchart.md` | Fetch → Parse → Classify → Dedup check → Extract metrics → Window aggregation → SLA check → Redis write → Metrics |
| **4. Sequence** | `4-sequence.md` | Kafka → Deduplication → Classification → Window update → SLA evaluation → Redis write → Metrics → Offset commit |
| **5. State Machine** | `5-state-machine.md` | Fetched → Deserialized → Classified → Deduped → Extracted → Windowed → SLACheck → MetricsEmit → Committed |
| **6. ER Diagram** | `6-er.md` | DOMAIN_EVENT, PROCESSOR_INPUT, DEDUPLICATION_RECORD, METRIC, WINDOW_ENTRY, SLA_EVALUATION, PROMETHEUS_RECORD |
| **7. E2E Flow** | `7-e2e.md` | Multi-source streaming → Dedup → Multi-window aggregations (30s/5m/1h) → SLA monitoring → Output sinks |

**Key Characteristics**:
- Idempotent processing via offset tracking
- Multi-window aggregations (30s, 5m, 1h)
- SLA monitoring with 30-min delivery compliance windows
- TTL-bounded Redis metrics cache
- Prometheus native metrics

---

## Diagram Set 6: Fraud Detection Service

**Language**: Java 21 / Spring Boot 3
**Pattern**: Multi-stage fraud scoring with 5 rule types + velocity tracking

| Diagram | File | Focus |
|---------|------|-------|
| **1. HLD** | `1-hld.md` | REST API + Kafka → Scoring → 5 Rules → Velocity → Blocklist → Outbox → Kafka fraud.events |
| **2. LLD** | `2-lld.md` | FraudController, FraudScoringService, RuleEvaluationService, VelocityService, BlocklistService, OutboxService |
| **3. Flowchart** | `3-flowchart.md` | Blocklist check → Rule loading → VELOCITY/AMOUNT/DEVICE/GEO/PATTERN eval → Score aggregation → Risk mapping → Action → Outbox |
| **4. Sequence** | `4-sequence.md` | Client → Scoring → Blocklist check → Rule evaluation → Velocity UPSERT → Score aggregation → Outbox → Metrics |
| **5. State Machine** | `5-state-machine.md` | Received → Validated → BlocklistCheck → RuleEval → ScoreAgg → RiskMap → ActionXxx → SignalPersist → OutboxPublish |
| **6. ER Diagram** | `6-er.md` | FRAUD_CHECK_REQUEST, BLOCKED_ENTITY, FRAUD_RULE, RULE_EVALUATION, VELOCITY_COUNTER, FRAUD_SIGNAL, OUTBOX_EVENT |
| **7. E2E Flow** | `7-e2e.md` | Request → Safety gates → 5-rule pipeline → Score aggregation → Risk/Action mapping → Persistence → Outbox → Events |

**Key Characteristics**:
- 5 rule types: VELOCITY (1h/24h windows), AMOUNT, DEVICE, GEO (Haversine), PATTERN
- Risk levels: LOW (0-25) → MEDIUM (26-50) → HIGH (51-75) → CRITICAL (76-100)
- Actions: ALLOW, FLAG, REVIEW, BLOCK
- Caffeine rule caching with admin invalidation
- Transactional outbox for fraud.events publishing

---

## Diagram Set 7: Audit Trail Service

**Language**: Java 21 / Spring Boot 3
**Pattern**: Immutable append-only audit log with monthly partitions

| Diagram | File | Focus |
|---------|------|-------|
| **1. HLD** | `1-hld.md` | REST API + 14 Kafka topics → DomainEventConsumer → Ingestion → Immutable audit_events (partitioned) → Search/Export |
| **2. LLD** | `2-lld.md` | AuditIngestionController, DomainEventConsumer, AuditIngestionService, AuditQueryService, AuditExportService, PartitionMaintenanceJob |
| **3. Flowchart** | `3-flowchart.md` | Parse event → Validate fields → Build with builder pattern → Determine partition → Insert → DLQ on error → Metrics |
| **4. Sequence** | `4-sequence.md` | Client/Kafka → Validation → Builder → Partition determination → Insert → DLQ on error → Metrics → Response |
| **5. State Machine** | `5-state-machine.md` | Received → Parsed → Validated → Built → Partitioned → Persisted → Success/Error → DLQ → Metrics → Response |
| **6. ER Diagram** | `6-er.md` | DOMAIN_EVENT, AUDIT_EVENT, AUDIT_PARTITION, AUDIT_QUERY_RESULT, CSV_EXPORT_JOB, DLQ_MESSAGE, METRICS_RECORD |
| **7. E2E Flow** | `7-e2e.md` | Multi-source ingestion (REST + 14 Kafka topics) → Validation → Immutable storage → Query/Export → Maintenance |

**Key Characteristics**:
- Immutable append-only (no UPDATE/DELETE)
- Monthly range partitioning
- 14 domain topics: identity, catalog, order, payment, inventory, fulfillment, rider, notification, search, pricing, promotion, support, returns, warehouse
- Paginated search with JPA Specifications
- Streaming CSV export (batch 500 rows)
- Compliance: 365-day retention
- DLQ for failed ingestion

---

## Diagram Quality Verification

All 49 diagrams have been verified against actual code:

### Source Code References

| Service | Source Files Verified |
|---------|----------------------|
| ai-inference-service | `main.py`, `models/*.py`, feature store logic |
| ai-orchestrator-service | `main.py`, `graph/`, `guardrails/`, LangGraph state |
| cdc-consumer-service | `main.go`, Debezium parsing, BigQuery transforms |
| location-ingestion-service | `main.go`, handlers, store, Redis/Kafka logic |
| stream-processor-service | `main.go`, processor types, window aggregation, SLA |
| fraud-detection-service | Spring Boot services, rule evaluation, velocity tracking |
| audit-trail-service | Spring Boot services, partitioning, export logic |

### Diagram Types Consistency

All services follow the same 7-diagram structure:

1. **HLD** - System-level architecture with external dependencies
2. **LLD** - Component class diagrams with responsibilities
3. **Flowchart** - Detailed request/event processing flow
4. **Sequence** - Interaction sequences between components
5. **State Machine** - State transitions for core entities
6. **ER Diagram** - Data model with relationships
7. **E2E Flow** - Complete end-to-end pipeline with latency budgets

---

## How to Use These Diagrams

### For Design Review
- Start with **HLD** to understand system architecture
- Review **Sequence** for interaction patterns
- Check **State Machine** for edge cases and recovery paths

### For Implementation
- Use **LLD** to understand component responsibilities
- Reference **Flowchart** for implementation logic
- Verify **ER** model for database design

### For Troubleshooting
- Use **State Machine** to understand current state
- Review **E2E Flow** to identify bottlenecks
- Check latency budgets in E2E diagrams

### For Monitoring
- Use E2E flow latency budgets to set alert thresholds
- Monitor SLOs per service based on documented guarantees
- Reference Prometheus metrics per diagram

---

## Regeneration Instructions

If you need to regenerate specific diagrams after code changes:

1. Identify the affected service
2. Review the code changes
3. Update the relevant diagram files (1-7)
4. Ensure consistency with other services' patterns
5. Update latency budgets and guarantees if changed

All diagrams use Mermaid syntax and are embedded in Markdown for easy viewing in GitHub/docs systems.

---

## Next Steps

- Commit all 49 diagrams to master branch
- Add to architecture documentation index
- Use for design review sessions
- Reference in runbooks and troubleshooting guides
- Update quarterly as architecture evolves

**Total Lines of Documentation**: ~2,500 (diagrams + descriptions)
**Verification Status**: ✅ All code-verified
**Production Ready**: ✅ Yes
