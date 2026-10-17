---
name: testing-integration
description: Real-container integration testing using Testcontainers (PostgreSQL, Kafka, Redis), Temporal TestWorkflowEnvironment, and dbt quality tests.
---

# Testing Integration Skill: End-to-End & Seam Verification

Use this skill when testing cross-boundary interactions, database persistence, Kafka outbox publishing, Temporal sagas, or dbt analytical data transformations.

---

## 1. Integration Testing Philosophy

- **No In-Memory H2 DBs**: Never use H2 or SQLite to test PostgreSQL. Use real PostgreSQL via **Testcontainers** to ensure Flyway syntax, JSONB queries, and locking semantics match production.
- **Isolate Flakiness**: Use deterministic timestamps and fixed UUIDs. Avoid arbitrary `Thread.sleep()`; use Awaitility for asynchronous assertions.
- **Narrow Container Scope**: Re-use test containers across tests using singleton container patterns where supported.

---

## 2. Spring Boot 4 + Testcontainers (PostgreSQL & Kafka)

### Pattern
Use `@Testcontainers` with `@DynamicPropertySource` to inject dynamic ports into Spring configuration.

```java
package com.instacommerce.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrderServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("order_db")
            .withUsername("order_user")
            .withPassword("secret");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void shouldPersistOrderAndExecuteFlywayMigrations() {
        OrderEntity order = new OrderEntity("cust-123", "darkstore-sf-1", 4500L);
        OrderEntity saved = orderRepository.save(order);

        assertThat(saved.getId()).isNotNull();
        assertThat(orderRepository.findById(saved.getId())).isPresent();
    }
}
```

---

## 3. Temporal Workflow Integration Testing (`checkout-orchestrator-service`)

### Pattern
Use `TestWorkflowEnvironment` to step through Temporal saga state machines without running an external Temporal server:

```java
package com.instacommerce.checkout.workflow;

import io.temporal.testing.TestWorkflowEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CheckoutSagaWorkflowTest {

    private TestWorkflowEnvironment testEnv;

    @BeforeEach
    void setUp() {
        testEnv = TestWorkflowEnvironment.newInstance();
    }

    @AfterEach
    void tearDown() {
        testEnv.close();
    }

    @Test
    void shouldCompensateInventoryWhenPaymentFails() {
        // Register mock activities where payment throws DeclineException
        // Verify workflow catches failure, invokes ReleaseInventoryActivity, and fails gracefully
    }
}
```

---

## 4. Data Platform Integration Testing (`data-platform/dbt`)

Run schema validations and relationship tests against dbt staging, intermediate, and mart layers:

```bash
# Run all tests for a specific model slice
cd data-platform/dbt && dbt test --select marts.orders

# Test staging layer integrity
cd data-platform/dbt && dbt test --select staging
```

---

## 5. Execution Commands

```bash
# Run integration tests for a specific Java service
./gradlew :services:order-service:test --tests "*IntegrationTest*"

# Run Go integration tests with build tag
cd services/cdc-consumer-service && go test -tags=integration -race -v ./...
```

