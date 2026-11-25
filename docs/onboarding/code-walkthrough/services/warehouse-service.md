# `warehouse-service` — Code Walkthrough

> [!NOTE]
> **Part of the InstaCommerce Onboarding Suite:** [Onboarding Hub](../../README.md) · [Code Walkthrough Atlas](../README.md) · [Domain & Event Catalog (`05`)](../../05-domain-model-and-event-catalog.md) · [Incident Drills & Exam (`06`)](../../06-incident-drills-and-readiness-exam.md) · [Dependency Matrix & Cheatsheet (`07`)](../../07-service-dependency-and-command-cheatsheet.md)

> **Dark-Store Topology, Zone Resolution, Two-Layer Capacity Locking, Dijkstra Floorplan Routing & Cold-Chain Gate**
>
> | Dimension | Verified Repo Truth |
> |---|---|
> | **Module & Runtime** | Java 21 / Spring Boot 4.0.0 ([build.gradle.kts](../../../../services/warehouse-service/build.gradle.kts#L1-L42)) · Caffeine (`stores`, `store-zones`, `store-hours`), ShedLock · Dependencies: `:libs:platform-common`, `:contracts` |
> | **HTTP Port** | `8092` (`${SERVER_PORT:8092}` in [application.yml](../../../../services/warehouse-service/src/main/resources/application.yml#L1-L4)) |
> | **Primary Storage** | PostgreSQL `warehouse`, Flyway `V1`–`V11` (`ddl-auto: validate`, `open-in-view: false`, Hikari `max=20`) — **zero Redis, zero Kafka consumers** |
> | **Kafka Topics** | **Produces** (via Go `outbox-relay-warehouse` `:8103`): `warehouse.events` (`StoreCreated`, `StoreUpdated`, `StoreStatusChanged`, `StoreCapacityUpdated`, `StoreCapacityThrottled`, `StoreCapacityPaused`, `BinAssigned`, `ColdChainBreachAlert`)<br>**Consumes:** none (`0` `@KafkaListener` beans) |
> | **Helm Keys** | `warehouse-service` ([values.yaml](../../../../deploy/helm/values.yaml)) · Relay: `outbox-relay-warehouse` ([values.yaml](../../../../deploy/helm/values.yaml#L1059)) |
> | **SLO Tier** | **Tier 2** (`99.0%` availability, `2000 ms` p99, `432 min/30d` budget) per [error-budget-policy.yaml](../../../../monitoring/error-budget-policy.yaml#L29-L44) |

---

## 1. Service Boundaries & Package Tree

`warehouse-service` is the physical dark-store registry and real-time operational capacity gatekeeper for quick-commerce fulfillment:
- **Upstream Consumers:** `checkout-orchestrator-service` (`:8091`) acquires and releases store capacity slots (`POST /stores/{id}/capacity/acquire` and `/release`); `mobile-bff-service` (`:8080`) resolves serviceable stores (`GET /stores/resolve`, `GET /stores/nearest`); `fulfillment-service` (`:8086`) caches store coordinates (`GET /stores/{id}`); `admin-gateway-service` (`:8099`) manages stores, delivery zones, operating hours, blackout windows, surge overrides, warehouse bins, and cold-chain telemetry.
- **No Kafka Consumption:** `warehouse-service` consumes zero Kafka topics; all outbound side effects write atomically into `outbox_events` (`outbox-relay-warehouse` -> `warehouse.events`).

### Package Layout (`com.instacommerce.warehouse`)

```text
services/warehouse-service/src/main/java/com/instacommerce/warehouse/
├── WarehouseServiceApplication.java         # @SpringBootApplication, Clock.systemUTC() bean, @EnableConfigurationProperties
├── config/
│   ├── CacheConfig.java                     # Caffeine (maxSize 2000, 300s): stores, store-zones, store-hours
│   ├── SecurityConfig.java                  # @Import(InternalServiceSecurityConfiguration) + permitAll/auth rules
│   ├── ShedLockConfig.java                  # JdbcTemplateLockProvider usingDbTime()
│   └── WarehouseProperties.java             # @ConfigurationProperties(prefix = "warehouse")
├── controller/
│   ├── StoreController.java                 # /stores (nearest, resolve, {id}, {id}/open, {id}/zones, delivery-radius, capacity)
│   ├── AdminStoreController.java            # /admin/stores (CRUD, status, zones, hours, surge, blackouts)
│   ├── BinController.java                   # /stores/{storeId}/bins (list, assign, by-sku)
│   ├── StagingTelemetryController.java      # /stores/{storeId}/staging/{telemetry, validate-dispatch}
│   └── StoreLayoutController.java           # POST /stores/{storeId}/layout/optimize-path
├── domain/layout/
│   ├── ItemCategory.java                    # weightOrder: HEAVY(1)->AMBIENT(2)->CHILLED(3)->FROZEN(4)->FRAGILE(5)
│   └── StoreLayoutGraph.java                # Static 10-aisle (A1..A10, 3.5m cross-aisles) Dijkstra floorplan graph
├── exception/
│   └── GlobalExceptionHandler.java          # Returns 409 CONFLICT on Optimistic/PessimisticLockingFailureException!
├── repository/
│   ├── StoreRepository.java                 # findNearestStores native SQL (divides by cos(radians(:lat)))
│   └── StoreCapacityRepository.java         # Atomic INSERT ... ON CONFLICT DO UPDATE WHERE current_orders < max_orders
└── service/
    ├── StoreService.java                    # selfProvider proxy for @Cacheable("store-hours"); soft-delete -> INACTIVE
    ├── ZoneService.java                     # Pincode primary lookup -> 15km Haversine fallback -> throttlePriority sort
    ├── CapacityService.java                 # Two-layer lock (stores FOR UPDATE + store_capacity atomic upsert)
    ├── CapacityAlertPublisher.java          # PROPAGATION_REQUIRES_NEW StoreCapacityPaused outbox emitter!
    ├── BinService.java                      # @Transactional lock on bin upsert + BinAssigned outbox
    ├── StagingTelemetryService.java         # CHILLED > 4.00C / FROZEN > -18.00C gate (uses Instant.now() directly)
    ├── StoreLayoutService.java              # Sorts items by weightOrder -> aisle -> Dijkstra pick path
    ├── OutboxService.java                   # Propagation.MANDATORY outbox_events writer
    └── OutboxCleanupJob.java                # Daily 03:00 ShedLock purge (1000/batch, 7d retention)
```

---

## 2. Startup, Configuration & Security Filter Chain

1. **Clock Bean ([WarehouseServiceApplication](../../../../services/warehouse-service/src/main/java/com/instacommerce/warehouse/WarehouseServiceApplication.java#L10-L22)):** Exposes `Clock.systemUTC()`, though several paths bypass it via `LocalDateTime.now()` and `Instant.now()`.
2. **Asymmetric Auth Matcher ([SecurityConfig](../../../../services/warehouse-service/src/main/java/com/instacommerce/warehouse/config/SecurityConfig.java#L1-L69)):** Imports `InternalServiceSecurityConfiguration` (`SharedInternalServiceAuthFilter`) and registers `JwtAuthenticationFilter`. Notice the asymmetry in `permitAll()` vs `authenticated()` under `/stores/**`:
   - **Unauthenticated `permitAll()` (`GET`):** `/stores/nearest`, `/stores/{id}`, `/stores/{id}/open`, `/stores/{id}/zones`, `/stores/{id}/delivery-radius`, `/stores/by-city`, `/stores/by-pincode`.
   - **Requires Authentication (falls through to `anyRequest().authenticated()`):** `GET /stores/resolve`, `POST /stores/{id}/capacity/**`, `/stores/{id}/bins/**`, `/stores/{id}/staging/**`, `/stores/{id}/layout/**`.
   - **Admin (`ADMIN` or `INTERNAL_SERVICE`):** `/admin/**` ([AdminStoreController](../../../../services/warehouse-service/src/main/java/com/instacommerce/warehouse/controller/AdminStoreController.java#L1-L162)).
3. **Lock Conflict Mapping:** Unlike `fulfillment-service` and `payment-service`, [GlobalExceptionHandler](../../../../services/warehouse-service/src/main/java/com/instacommerce/warehouse/exception/GlobalExceptionHandler.java#L72-L76) explicitly catches both `OptimisticLockingFailureException` and `PessimisticLockingFailureException` and returns `HTTP 409 CONFLICT` (`CONFLICT`) instead of `500`.

---

## 3. PostgreSQL Schema (`V1`–`V11`) & Capacity Tables

All `11` Flyway scripts under [migration/](../../../../services/warehouse-service/src/main/resources/db/migration/) enforce dark-store configuration and telemetry invariants:

| Migration Range | Tables & Key Constraints |
|---|---|
| `V1`–`V3`, `V7` | `stores` (`code` unique, `status` ENUM `ACTIVE`/`INACTIVE`/`MAINTENANCE`, `max_concurrent_orders` default `100`, `current_pending_orders`, `V7` adds `version` + `timezone`), `store_zones` (delivery pincodes/radius), `operating_hours` (weekday `0–6` open/close, including overnight spans). |
| `V4`, `V9`, `V11` | `store_capacity` (unique `(store_id, date, hour)`, `current_orders`, `max_orders`, `throttle_threshold_pct`); `V9` adds `warehouse_bins` and `staging_temperature_telemetry`; `V11` adds `surge_mode_enabled`, `throttle_eta_extension_minutes`, and `store_blackout_windows`. |
| `V5`, `V6`, `V8`, `V10` | `outbox_events` (`V8` aligns `EventEnvelope` fields + `schema_version = 'v1'`, `V10` adds retention index) and `shedlock` (`V6`). |

---

## 4. Architecture & Low-Level Component Topology

**warehouse-service Architecture LLD** — *[Rendered PNG](../diagrams/services/warehouse-service-arch-lld.png) · [Mermaid Source](../diagrams/services/warehouse-service-arch-lld.mmd)*

```mermaid
flowchart TB
    classDef caller fill:#F8FAFC,stroke:#475569,stroke-width:1.5px,color:#0F172A
    classDef sec fill:#FEF3C7,stroke:#D97706,stroke-width:1.6px,color:#78350F
    classDef ctrl fill:#EFF6FF,stroke:#2563EB,stroke-width:1.6px,color:#0F172A
    classDef svc fill:#EEF2FF,stroke:#4F46E5,stroke-width:1.6px,color:#0F172A
    classDef db fill:#ECFDF5,stroke:#059669,stroke-width:1.6px,color:#0F172A
    classDef warn fill:#FEF2F2,stroke:#DC2626,stroke-width:1.8px,stroke-dasharray:5 3,color:#7F1D1D

    subgraph CALLERS["Inbound Callers (Zero Kafka Consumption)"]
        direction LR
        BFF["mobile-bff :8080 / checkout :8091<br/>resolve · capacity/acquire · release"]:::caller
        FUL["fulfillment-service :8086<br/>GET /stores/{storeId} coords"]:::caller
        ADM["admin-gateway :8099<br/>/admin/stores/** · surge · blackouts"]:::caller
    end

    subgraph WH["warehouse-service :8092 (Java 21 · Spring Boot 4.0.0 · Hikari max=20)"]
        direction TB
        SEC["SecurityConfig (@Import InternalServiceSecurityConfiguration)<br/>permitAll: /stores/nearest, /{id}, /open, /zones, /by-city, /by-pincode<br/>authenticated: /stores/resolve (!), /capacity, /bins, /layout<br/>ADMIN | INTERNAL_SERVICE: /admin/**"]:::sec

        subgraph CTRLS["controller/ (5 Controllers)"]
            direction LR
            SC["StoreController<br/>/stores/{nearest,resolve,by-pincode,capacity}"]:::ctrl
            ASC["AdminStoreController<br/>/admin/stores CRUD, zones, hours, surge, blackouts"]:::ctrl
            BC["BinController<br/>/stores/{storeId}/bins/{assign,by-sku}"]:::ctrl
            STC["StagingTelemetryController<br/>/staging/{telemetry,validate-dispatch}"]:::ctrl
            SLC["StoreLayoutController<br/>POST /layout/optimize-path"]:::ctrl
        end

        subgraph SERVICES["service/, domain/layout/ & Caching"]
            direction LR
            SS["StoreService (selfProvider proxy)<br/>timezone-aware isStoreOpen + readiness check<br/>soft-delete -&gt; INACTIVE"]:::svc
            ZS["ZoneService<br/>PRIMARY_PINCODE -&gt; FALLBACK_NEAREST (15km)"]:::svc
            CS["CapacityService + CapacityAlertPublisher<br/>FOR UPDATE + hourly ON CONFLICT<br/>REQUIRES_NEW StoreCapacityPaused"]:::svc
            BS_TS["BinService (FOR UPDATE bin lock)<br/>StagingTelemetryService (CHILLED 4C / FROZEN -18C)<br/>StoreLayoutService (static 10-aisle Dijkstra)"]:::svc
            CAF["Caffeine CacheConfig (2000, 300s)<br/>stores · store-zones · store-hours"]:::svc
        end

        subgraph JOBS["Outbox & ShedLock Jobs"]
            direction LR
            OB["OutboxService (MANDATORY)<br/>Store* · StoreCapacity* · BinAssigned · ColdChainBreachAlert"]:::svc
            CRON["CapacityService.cleanupOldCapacityData (00:00, 7d)<br/>OutboxCleanupJob (03:00, 1000/batch, 7d)"]:::svc
        end
    end

    subgraph DATA["PostgreSQL warehouse DB (V1..V11) & Outbox"]
        direction LR
        PG["PostgreSQL Tables:<br/>stores (@Version, max_concurrent=100, surge_*)<br/>store_zones · store_hours · store_capacity<br/>store_blackout_windows (V11)<br/>warehouse_bins (V9) · staging_temperature_telemetry (V9)<br/>outbox_events (V8 v1) · shedlock"]:::db
        RELAY["outbox-relay-warehouse :8103 (Go)<br/>polls outbox_events -&gt; warehouse.events"]:::db
        DRIFT["Code Notes:<br/>Static A1..A10 graph ignores warehouse_bins<br/>StoreController passes LocalDateTime.now()"]:::warn
    end

    BFF & FUL & ADM --> SEC --> CTRLS
    SC --> SS & ZS & CS
    ASC --> SS & ZS & CS
    BC --> BS_TS
    STC --> BS_TS
    SLC --> BS_TS
    SS & ZS & CS & BS_TS --> OB --> PG
    SS & ZS --> CAF
    CS & OB --> PG
    CRON --> PG
    PG --> RELAY
    BS_TS -.- DRIFT
```

**Key Component Interactions:**
- **Self-Proxy Caching Pattern ([StoreService](../../../../services/warehouse-service/src/main/java/com/instacommerce/warehouse/service/StoreService.java#L242)):** `StoreService` injects `ObjectProvider<StoreService> selfProvider` so internal calls like `checkStoreHours(...)` traverse the Spring AOP proxy and hit Caffeine cache `store-hours` (`2000` entries, `300s`).
- **Two-Layer Readiness Validation:** [StoreService.updateStoreStatus](../../../../services/warehouse-service/src/main/java/com/instacommerce/warehouse/service/StoreService.java#L291-L293) runs operating-hours/zone readiness checks *only* when transitioning **into** `ACTIVE`, and `deleteStore` performs a soft delete (`status = INACTIVE`) rather than deleting rows.

---

## 5. Zone Fallback, Two-Layer Capacity Locking, Dijkstra & Cold-Chain Gate

**warehouse-service Flowchart** — *[Rendered PNG](../diagrams/services/warehouse-service-flowchart.png) · [Mermaid Source](../diagrams/services/warehouse-service-flowchart.mmd)*

```mermaid
flowchart TD
    classDef start fill:#EFF6FF,stroke:#2563EB,stroke-width:1.6px,color:#0F172A
    classDef dec fill:#FEF3C7,stroke:#D97706,stroke-width:1.6px,color:#78350F
    classDef ok fill:#ECFDF5,stroke:#059669,stroke-width:1.6px,color:#0F172A
    classDef err fill:#FEF2F2,stroke:#DC2626,stroke-width:1.8px,color:#7F1D1D
    classDef step fill:#EEF2FF,stroke:#4F46E5,stroke-width:1.5px,color:#0F172A

    subgraph ZONERES["1. Zone Resolution & Capacity Locking"]
        direction TB
        Z_IN(["resolveStoresForOrder(pincode, lat, lng)"]):::start --> PRIM["Evaluate ACTIVE stores mapped to pincode<br/>isStoreOpen (blackouts + overnight hours) &amp; canAcceptOrders"]:::step
        PRIM --> PRIM_OK{"Any eligible<br/>PRIMARY_PINCODE?"}:::dec
        PRIM_OK -->|Yes| SORT_Z["Sort by throttlePriority:<br/>NORMAL -&gt; SURGE -&gt; THROTTLED, then etaExtension"]:::ok
        PRIM_OK -->|No| H_FB["FALLBACK_NEAREST: Haversine 15km (max 10)<br/>or findByStatus(ACTIVE) if coords null"]:::step
        H_FB --> SORT_Z

        ACQ(["acquireOrderSlot(storeId)"]):::start --> LOCK_S["SELECT s FROM stores FOR UPDATE"]:::step
        LOCK_S --> OPEN_CHK{"ACTIVE &amp; not in<br/>blackout window?"}:::dec
        OPEN_CHK -->|No| ERR_CL["Throw StoreClosedException (409/423)"]:::err
        OPEN_CHK -->|Yes| CONC_CHK{"currentPending<br/>&gt;= maxConcurrent?"}:::dec
        CONC_CHK -->|Yes| PAUSE1["REQUIRES_NEW: publishCapacityPaused<br/>throw CapacityExceededException (429)"]:::err
        CONC_CHK -->|No| SURGE_CHK["If surgeMode OR pending+1 &gt;= ceil(max*pct):<br/>emit StoreCapacityThrottled; pending++"]:::step
        SURGE_CHK --> HR_UP["store_capacity ON CONFLICT DO UPDATE<br/>WHERE current_orders &lt; max_orders"]:::step
        HR_UP --> HR_OK{"rows &gt; 0?"}:::dec
        HR_OK -->|Yes| ACQ_OK["Return true (slot acquired)"]:::ok
        HR_OK -->|No| PAUSE2["REQUIRES_NEW: publishCapacityPaused<br/>throw CapacityExceededException (outer TX rolls back!)"]:::err
    end

    subgraph DIJK["2. Dijkstra Floorplan & Cold-Chain Gate"]
        direction TB
        D_IN(["optimizePickPath(items)"]):::start --> WT["Sort by ItemCategory.weightOrder<br/>(HEAVY 1 -&gt; AMBIENT 2 -&gt; CHILLED 3 -&gt; FROZEN 4 -&gt; FRAGILE 5)<br/>then aisle"]:::step
        WT --> GRAPH["Dijkstra across default A1..A10 graph<br/>ENTRANCE -&gt; items -&gt; PACKING (3.5m cross-aisles)<br/>est = round(dist / 1.2 + items * 15s)"]:::ok
        T_IN(["recordTelemetry / validateDispatch"]):::start --> BREACH{"CHILLED &gt; 4.00C<br/>or FROZEN &gt; -18.00C?"}:::dec
        BREACH -->|Yes &amp; priorBreaches &gt;= 1 in 5m| ALERT["Set alertTriggered=true<br/>emit ColdChainBreachAlert"]:::err
        BREACH -->|Dispatch CHILLED/FROZEN| BAG{"hasInsulatedThermalBag<br/>&amp;&amp; hasColdPacks?"}:::dec
        BAG -->|No| PKG_ERR["Throw ColdChainPackagingViolationException"]:::err
        BAG -->|Yes| PKG_OK["DispatchPackagingResponse(valid=true)"]:::ok
    end
```

### 1. Pincode Primary & Haversine Fallback Resolution ([ZoneService](../../../../services/warehouse-service/src/main/java/com/instacommerce/warehouse/service/ZoneService.java#L1-L237))
- `resolveStoresForOrder(pincode, lat, lng)` first filters `ACTIVE` stores mapped to `pincode` in `store_zones` whose store is open (checking `store_blackout_windows` + overnight `operating_hours`) and passes capacity eligibility (`canAcceptOrders`).
- If **zero** `PRIMARY_PINCODE` matches qualify, it falls back (`FALLBACK_NEAREST`) to `StoreRepository.findNearestStores` within `15 km` (max `10` results), or `findByStatus(ACTIVE)` if caller coordinates are null.
- Matches are sorted by `throttlePriority` (`NORMAL` -> `SURGE` -> `THROTTLED`) and then `etaExtensionMinutes`.

### 2. Static `A1`–`A10` Dijkstra Floorplan & Weight Ordering ([StoreLayoutService](../../../../services/warehouse-service/src/main/java/com/instacommerce/warehouse/service/StoreLayoutService.java#L1-L105), [StoreLayoutGraph](../../../../services/warehouse-service/src/main/java/com/instacommerce/warehouse/domain/layout/StoreLayoutGraph.java#L101-L154))
- `POST /stores/{storeId}/layout/optimize-path` sorts request items by `ItemCategory.weightOrder` ([ItemCategory](../../../../services/warehouse-service/src/main/java/com/instacommerce/warehouse/domain/layout/ItemCategory.java#L1-L51): `HEAVY 1 -> AMBIENT 2 -> CHILLED 3 -> FROZEN 4 -> FRAGILE 5`), then by aisle, then runs Dijkstra shortest path from `ENTRANCE` across all item nodes to `PACKING`.
- **Important Code Truth:** `StoreLayoutGraph.createDefaultDarkStoreFloorplan` builds a **hardcoded** `10`-aisle (`A1`–`A10`, `3.5m` cross-aisle edges) graph shared identically across all stores, ignoring actual `warehouse_bins` coordinates (`estSeconds = round(distance / 1.2 + items * 15s)`).

---

## 6. Call-Stack Sequence: Two-Layer Capacity Acquire & `REQUIRES_NEW` Alerting

**warehouse-service Sequence Diagram** — *[Rendered PNG](../diagrams/services/warehouse-service-sequence.png) · [Mermaid Source](../diagrams/services/warehouse-service-sequence.mmd)*

```mermaid
sequenceDiagram
    autonumber
    participant Caller as Checkout / Fulfillment / Sensor
    participant Ctrl as StoreController /<br/>StagingTelemetryController
    participant CS as CapacityService /<br/>StoreService
    participant CAP as CapacityAlertPublisher<br/>(REQUIRES_NEW)
    participant PG as PostgreSQL<br/>(warehouse DB)
    participant Outbox as OutboxService<br/>(MANDATORY)

    Note over Caller,Outbox: 1. Two-Layer Capacity Slot Acquisition (POST /stores/{id}/capacity/acquire)
    Caller->>Ctrl: acquireOrderSlot(storeId)
    Ctrl->>CS: acquireOrderSlot(storeId, LocalDateTime.now())
    CS->>PG: SELECT * FROM stores WHERE id = :id FOR UPDATE
    CS->>PG: SELECT EXISTS blackout window at storeNow
    alt Store inactive or in blackout window
        CS-->>Caller: 423 / 409 StoreClosedException
    else currentPendingOrders ≥ maxConcurrentOrders
        CS->>CAP: publishCapacityPaused(storeId, payload) [REQUIRES_NEW]
        CAP->>Outbox: INSERT outbox_events (StoreCapacityPaused)
        CAP-->>CS: committed independently!
        CS-->>Caller: 429 CapacityExceededException
    else Concurrent slot available
        opt surgeModeEnabled OR (pending + 1) ≥ throttleThreshold
            CS->>Outbox: INSERT outbox_events (StoreCapacityThrottled) [same TX]
        end
        CS->>PG: UPDATE stores SET current_pending_orders = pending + 1
        CS->>PG: INSERT INTO store_capacity ... ON CONFLICT (store_id,date,hour) DO UPDATE WHERE current_orders < max_orders
        alt Updated rows == 0 (hourly cap full)
            CS->>CAP: publishCapacityPaused(storeId, payload) [REQUIRES_NEW]
            CAP->>PG: INSERT outbox_events (StoreCapacityPaused)
            CS-->>Caller: 429 CapacityExceededException (outer pending++ rolled back!)
        else Updated rows == 1
            CS-->>Caller: 200 true
        end
    end

    Note over Caller,Outbox: 2. Cold-Chain Staging Breach & Dispatch Gate
    Caller->>Ctrl: POST /stores/{storeId}/staging/telemetry
    Ctrl->>CS: recordTelemetry(storeId, req)
    CS->>PG: COUNT breached=true for (storeId, bayId) in last 5m
    opt breached && priorBreaches ≥ 1
        CS->>Outbox: INSERT outbox_events (ColdChainBreachAlert)
    end
    CS->>PG: INSERT staging_temperature_telemetry
    CS-->>Caller: 201 StagingTelemetryResponse
```

### Why `CapacityAlertPublisher` Needs `Propagation.REQUIRES_NEW`
Walk through [CapacityService.acquireOrderSlot](../../../../services/warehouse-service/src/main/java/com/instacommerce/warehouse/service/CapacityService.java#L94-L139):
1. Outer `@Transactional` acquires `stores` row via `findByIdForUpdate` and checks `store_blackout_windows` at `storeNow`. Inactive or blacked-out stores throw `StoreClosedException` (`423` / `409`).
2. **Concurrent Order Cap Layer (`stores.current_pending_orders` vs `max_concurrent_orders`):** If `currentPendingOrders >= maxConcurrentOrders`, `CapacityService` calls [CapacityAlertPublisher.publishCapacityPaused](../../../../services/warehouse-service/src/main/java/com/instacommerce/warehouse/service/CapacityAlertPublisher.java#L1-L21) (`Propagation.REQUIRES_NEW`), commits a `StoreCapacityPaused` outbox event on an independent transaction, and then throws `CapacityExceededException` (`HTTP 429`). Without `REQUIRES_NEW`, throwing `CapacityExceededException` would roll back the very alert event notifying operations that the store hit capacity!
3. **Throttle Check + Hourly Cap Layer (`store_capacity`):** If `surgeModeEnabled` OR `(pending + 1) >= ceil(max * pct)`, it emits `StoreCapacityThrottled` inside the main transaction, increments `currentPendingOrders`, and executes [StoreCapacityRepository.incrementOrderCount](../../../../services/warehouse-service/src/main/java/com/instacommerce/warehouse/repository/StoreCapacityRepository.java#L1-L32) (`INSERT ... ON CONFLICT (store_id, date, hour) DO UPDATE SET current_orders = current_orders + 1 WHERE current_orders < max_orders`).
4. If the atomic hourly `UPSERT` returns `0` rows (hourly cap reached), `CapacityService` again calls `CapacityAlertPublisher.publishCapacityPaused` (`REQUIRES_NEW`) and throws `CapacityExceededException`, rolling back the outer `current_pending_orders++` while preserving `StoreCapacityPaused` in `outbox_events`!

### Cold-Chain Telemetry & Dispatch Gate ([StagingTelemetryService](../../../../services/warehouse-service/src/main/java/com/instacommerce/warehouse/service/StagingTelemetryService.java#L1-L155))
- `POST /stores/{storeId}/staging/telemetry` flags breaches (`CHILLED > 4.00C` or `FROZEN > -18.00C`). If breached and `>= 1` prior breach exists in the same `(storeId, bayId)` within the last `5m`, it sets `alertTriggered = true` and emits `ColdChainBreachAlert`.
- `POST /stores/{storeId}/staging/validate-dispatch` rejects orders containing `CHILLED` or `FROZEN` items unless `hasInsulatedThermalBag && hasColdPacks`, throwing `ColdChainPackagingViolationException`.

---

## 7. Scheduled Jobs & Outbox Cleanup

- **[CapacityService.cleanupOldCapacityData](../../../../services/warehouse-service/src/main/java/com/instacommerce/warehouse/service/CapacityService.java#L240-L270):** Runs daily at `00:00` under ShedLock, pruning `store_capacity` history older than `7` days.
- **[OutboxCleanupJob](../../../../services/warehouse-service/src/main/java/com/instacommerce/warehouse/service/OutboxCleanupJob.java#L1-L39):** Runs daily at `03:00` under ShedLock (`1000` rows/batch, `7`-day retention for `sent = true` events).
- **[BinService](../../../../services/warehouse-service/src/main/java/com/instacommerce/warehouse/service/BinService.java#L1-L146):** Acquires `FOR UPDATE` row lock on bin SKU assignment (`warehouse_bins`) and writes `BinAssigned` via `OutboxService`.

---

## 8. Configuration & Narrow Test Verification

- **Key Properties ([application.yml](../../../../services/warehouse-service/src/main/resources/application.yml#L1-L90)):** `SERVER_PORT:8092`, `hikari.maximum-pool-size: 20`, `hibernate.jakarta.persistence.lock.timeout` via repo hints, Caffeine TTL `300s`.
- **Tests:** `9` test classes live under `services/warehouse-service/src/test` (e.g., `CapacityServiceTest`, `StoreBlackoutSurgeAndFallbackTest`, `WarehouseRemediationTest`, `WarehouseIntegrationTest`). Run narrow checks via:

```bash
./gradlew :services:warehouse-service:test --tests "com.instacommerce.warehouse.service.CapacityServiceTest"
```

---

## 9. Must-Fix Code Risks & Severe README Drift

| Severity | Finding | Exact Evidence & Impact |
|---|---|---|
| **HIGH** | **Pole division-by-zero edge math in `StoreRepository.findNearestStores`** | [StoreRepository](../../../../services/warehouse-service/src/main/java/com/instacommerce/warehouse/repository/StoreRepository.java#L36-L37) divides longitude bounding-box delta by `cos(radians(:lat))`, even though [StoreController.findNearestStores](../../../../services/warehouse-service/src/main/java/com/instacommerce/warehouse/controller/StoreController.java#L50-L70) permits `lat` in `[-90.0, 90.0]` (`cos(+-90 deg) = 0`). |
| **MEDIUM** | **JVM-default timezone coupling in controllers & uninjected `Clock` in telemetry** | [StoreController](../../../../services/warehouse-service/src/main/java/com/instacommerce/warehouse/controller/StoreController.java#L59-L66) and [AdminStoreController](../../../../services/warehouse-service/src/main/java/com/instacommerce/warehouse/controller/AdminStoreController.java#L84-L90) pass `LocalDateTime.now()` (JVM default zone) which `toStoreTime` re-interprets via `ZoneId.systemDefault()`, while [StagingTelemetryService](../../../../services/warehouse-service/src/main/java/com/instacommerce/warehouse/service/StagingTelemetryService.java#L53-L124) calls `Instant.now()` directly instead of the `Clock` bean. |
| **MEDIUM** | **`/stores/resolve` auth asymmetry + static floorplan ignoring `warehouse_bins`** | [SecurityConfig](../../../../services/warehouse-service/src/main/java/com/instacommerce/warehouse/config/SecurityConfig.java#L45-L48) leaves `/stores/nearest` unauthenticated while `/stores/resolve` requires auth. Meanwhile `StoreLayoutService` computes paths on the static `A1`–`A10` floorplan ignoring per-store `warehouse_bins`. |
| **DRIFT** | **`warehouse-service/README.md` contradicts source across 7 core areas** | [warehouse-service/README.md](../../../../services/warehouse-service/README.md) falsely claims port `8090` (actual `8092`), `V1`–`V10` (actual `V1`–`V11`), hard store deletion (`deleteStore` sets `INACTIVE`), missing zone/hours CRUD (`AdminStoreController` has full CRUD + surge + blackouts), HTTP `500` on OCC (`GlobalExceptionHandler` returns `409`), `30`-day outbox retention (actual `7` days), and `"No test source directory exists"` (`9` test classes exist). |

---

**Previous:** [fraud-detection-service](./fraud-detection-service.md) | **Atlas Hub:** [Code Walkthrough Atlas](../README.md) | **Next:** [fulfillment-service](./fulfillment-service.md)
