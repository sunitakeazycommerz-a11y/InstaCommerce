# Spring Boot 4 Upgrade Plan

**Commitment**: Comprehensive, careful, production-grade migration from Spring Boot 3.3.4 to 4.0.0+

## Phase 1: Analysis & Requirements

### Spring Boot 4.0.0 Requirements
| Component | Current | Required | Status |
|-----------|---------|----------|--------|
| Java Version | 17+ (CI: 21) | **21+** | ✅ Compatible |
| Spring Framework | 6.1.x | **7.0.x** | 🔄 Upgrade needed |
| Spring Kafka | 3.2.4 | **4.x** | 🔄 Upgrade needed |
| Jakarta EE | 10 | **11** | 🔄 Upgrade needed |
| Gradle | 8.x+ | 8.x+ | ✅ Compatible |

### Breaking Changes in Spring Boot 4
1. **Namespace**: `javax.*` → `jakarta.*` (ALL imports)
2. **Configuration**: `server-servlet-* → server.*` (properties)
3. **Dependency Management**: Spring Cloud Kafka 4.x, Cloud GCP 6.x
4. **Removed**: spring-boot-starter-kafka → Direct spring-kafka import only

## Phase 2: Implementation Strategy

### Step 1: Update build.gradle.kts
- [ ] Update Spring Boot plugin from 3.3.4 to 4.0.0
- [ ] Update io.spring.dependency-management to 1.1.8+
- [ ] Update spring-boot-dependencies BOM to 4.0.0
- [ ] Update Spring Cloud dependencies to 2024.0.0
- [ ] Update spring-kafka to 4.0.0+
- [ ] Update GCP dependencies to 6.0.0+
- [ ] Update Jackson to 2.18.6+ (compatible)
- [ ] Create gradle.properties with Java 21 requirement

### Step 2: Code Changes (All Services)
For each of 28 Java services:
- [ ] Replace `javax.*` imports → `jakarta.*`
- [ ] Update servlet filters, annotations, persistence
- [ ] Update Spring Cloud stream bindings
- [ ] Fix Kafka consumer/producer configurations
- [ ] Update test configurations

### Step 3: Configuration Updates
- [ ] Update application.yml/properties files
- [ ] Fix server servlet configuration keys
- [ ] Update actuator endpoints
- [ ] Update health checks

### Step 4: Dependency Fixes
- [ ] Handle Python environment conflicts (numpy, langchain-core)
- [ ] Fix Go service imports (if any SB 4 indirect deps)

### Step 5: Testing & Validation
- [ ] Build all 13 Java services locally
- [ ] Run integration tests (74+ tests from Wave 37)
- [ ] Validate CI pipeline
- [ ] SLO compliance check

## Phase 3: Service Inventory (28 Services)

### Java Services (13)
**Money Path** (6):
1. checkout-orchestrator-service
2. order-service
3. payment-service
4. inventory-service
5. fulfillment-service
6. payment-webhook-service

**Platform** (5):
7. identity-service
8. admin-gateway-service
9. config-feature-flag-service
10. audit-trail-service
11. mobile-bff-service

**Engagement** (2):
12. notification-service
13. fraud-detection-service

### Go Services (10)
1. cdc-consumer-service
2. dispatch-optimizer-service
3. location-ingestion-service
4. outbox-relay-service
5. reconciliation-engine
6. routing-eta-service (java or go?)
7. stream-processor-service
8. wallet-loyalty-service (java or go?)
9. warehouse-service (java or go?)
10. rider-fleet-service (java or go?)

### Python Services (3)
1. ai-inference-service
2. ai-orchestrator-service
3. search-service (java or python?)

### Contracts
- contracts module (build.gradle.kts)

## Phase 4: Detailed Changes

### 4.1: Root build.gradle.kts
```diff
- id("org.springframework.boot") version "3.3.4" apply false
+ id("org.springframework.boot") version "4.0.0" apply false

- id("io.spring.dependency-management") version "1.1.7" apply false
+ id("io.spring.dependency-management") version "1.1.8" apply false

- mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.4")
+ mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.0")

- mavenBom("org.springframework.cloud:spring-cloud-dependencies:2023.0.3")
+ mavenBom("org.springframework.cloud:spring-cloud-dependencies:2024.0.0")

- mavenBom("com.google.cloud:spring-cloud-gcp-dependencies:5.1.0")
+ mavenBom("com.google.cloud:spring-cloud-gcp-dependencies:6.0.0")

+ dependency("org.springframework.kafka:spring-kafka:4.0.0")
+ dependency("org.springframework.kafka:spring-kafka-test:4.0.0")
```

### 4.2: gradle.properties (CREATE NEW)
```
org.gradle.java.home=/usr/lib/jvm/java-21-openjdk
java.sourceCompatibility=21
java.targetCompatibility=21
gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m
```

### 4.3: Service pom equivalent (individual service build.gradle.kts)
Example: services/order-service/build.gradle.kts
```diff
dependenc {
    implementation("org.springframework.boot:spring-boot-starter-web")
    // All deps auto-managed by Spring Boot 4.0.0 BOM
}
```

## Phase 5: Cautionary Notes

### Code Changes Scope
- **EXTENSIVE**: Every javax.* import → jakarta.*
- **MODERATE**: Configuration file adjustments
- **MINIMAL**: Logic code (no business logic changes)

### Services Most Affected
1. **Spring Web services** (servlet imports): ALL web services
2. **Spring Data services** (JPA): inventory, catalog, order, payment
3. **Spring Cloud Stream**: outbox-relay, cdc-consumer, stream-processor
4. **Kafka services**: All services using Kafka consumers/producers

### Rollback Plan
- Git branch: preserve pre-upgrade state on `spring-boot-3.3.4` branch
- Commit strategy: One commit per service + root upgrade
- Testing: Full integration test pass before merge

## Phase 6: Validation Checklist

- [ ] Root build.gradle.kts: 4.0.0 + dependencies updated
- [ ] gradle.properties: Java 21 requirement set
- [ ] All 13 Java services: javax.*→jakarta.* conversion complete
- [ ] Payment service: Money path tests pass
- [ ] Order service: Order flow tests pass
- [ ] Feature flags: Config service tests pass
- [ ] Notifications: Event processing tests pass
- [ ] CI pipeline: No compilation errors
- [ ] Integration tests: 74+ tests green
- [ ] SLO compliance: All metrics within targets
- [ ] No breaking changes: Wave 34-39 features intact

## Success Criteria

✅ **0 Compilation Errors**
✅ **All 74+ Integration Tests Pass**
✅ **CI Pipeline Green**
✅ **SLO Targets Met** (latency, availability, error rate)
✅ **Production Commit Ready**

---

**Status**: Planned → Ready for implementation
**Complexity**: High (scope: 13 Java services, 100+ files)
**Risk**: Medium (upgrade path well-documented, Spring provides migration guide)
**Timeline**: 2-4 hours per service × 13 = 26-52 hours total (parallel on 4 services = 7-13 hours wall time)
