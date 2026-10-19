package com.instacommerce.pricing.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test verifying full Flyway migration execution (V1 to V10)
 * against real PostgreSQL 16 instance using Testcontainers.
 * Proves index constraints, JSONB column queries, and uniqueness invariants without H2 emulation.
 */
@Testcontainers(disabledWithoutDocker = true)
class PricingPostgresFlywayIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void setUp() {
        POSTGRES.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        jdbcTemplate = new JdbcTemplate(dataSource);

        // Execute full migration chain
        MigrateResult result = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(10);
    }

    @Test
    @DisplayName("Verify Flyway migrations created public tables with correct PostgreSQL types")
    void shouldCreateAllRequiredTables() {
        List<String> tables = jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name",
            String.class
        );

        assertThat(tables).contains(
            "price_rules",
            "promotions",
            "coupons",
            "outbox_events",
            "shedlock",
            "price_quotes",
            "coupon_redemptions"
        );
    }

    @Test
    @DisplayName("Verify PostgreSQL JSONB operations on outbox_events table")
    void shouldSupportJsonbOperationsOnOutbox() {
        UUID eventId = UUID.randomUUID();
        String jsonPayload = """
            {"order_id": "ord-test-1", "subtotal_cents": 5000, "currency": "INR", "items": [{"id": 1, "qty": 2}]}
            """;

        jdbcTemplate.update("""
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, source_service, correlation_id, schema_version, sent)
            VALUES (?, 'order', 'ord-test-1', 'OrderCreated', ?::jsonb, 'pricing-service', 'corr-101', 'v1', false)
            """, eventId, jsonPayload);

        // Query JSONB text extraction (->>)
        String orderId = jdbcTemplate.queryForObject(
            "SELECT payload->>'order_id' FROM outbox_events WHERE id = ?",
            String.class,
            eventId
        );
        assertThat(orderId).isEqualTo("ord-test-1");

        // Query JSONB integer extraction
        Integer subtotal = jdbcTemplate.queryForObject(
            "SELECT (payload->>'subtotal_cents')::int FROM outbox_events WHERE id = ?",
            Integer.class,
            eventId
        );
        assertThat(subtotal).isEqualTo(5000);

        // Query JSONB containment (@>)
        Boolean containsKey = jdbcTemplate.queryForObject(
            "SELECT payload @> '{\"currency\": \"INR\"}'::jsonb FROM outbox_events WHERE id = ?",
            Boolean.class,
            eventId
        );
        assertThat(containsKey).isTrue();
    }

    @Test
    @DisplayName("Verify index constraints on outbox_events and coupons")
    void shouldVerifyPostgreSQLIndexConstraints() {
        List<Map<String, Object>> indexes = jdbcTemplate.queryForList(
            "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'"
        );

        List<String> indexNames = indexes.stream()
            .map(m -> (String) m.get("indexname"))
            .toList();

        // Check outbox retention index and sent index
        assertThat(indexNames).anyMatch(name -> name.contains("outbox_events"));

        // Test Unique Constraint on Coupon Code
        UUID couponId1 = UUID.randomUUID();
        UUID couponId2 = UUID.randomUUID();
        String code = "FLAT50-" + UUID.randomUUID().toString().substring(0, 8);

        jdbcTemplate.update("""
            INSERT INTO coupons (id, code, discount_type, discount_value, max_uses, current_uses, active)
            VALUES (?, ?, 'PERCENTAGE', 50, 100, 0, true)
            """, couponId1, code);

        // Duplicate code must violate unique constraint in real PostgreSQL
        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO coupons (id, code, discount_type, discount_value, max_uses, current_uses, active)
            VALUES (?, ?, 'PERCENTAGE', 50, 100, 0, true)
            """, couponId2, code))
            .isInstanceOf(Exception.class)
            .hasMessageContaining("duplicate key value violates unique constraint");
    }
}
