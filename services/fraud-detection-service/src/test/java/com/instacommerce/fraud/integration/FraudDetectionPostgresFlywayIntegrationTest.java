package com.instacommerce.fraud.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
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
 * Integration test verifying full Flyway migration execution (V1 to V9)
 * against real PostgreSQL 16 instance using Testcontainers for Fraud Detection Service.
 */
@Testcontainers(disabledWithoutDocker = true)
class FraudDetectionPostgresFlywayIntegrationTest {

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
        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(9);
    }

    @Test
    @DisplayName("Verify Flyway migrations created fraud detection public tables")
    void shouldCreateFraudTables() {
        List<String> tables = jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name",
            String.class
        );

        assertThat(tables).contains(
            "fraud_rules",
            "fraud_signals",
            "velocity_counters",
            "blocked_entities",
            "outbox_events",
            "shedlock"
        );
    }

    @Test
    @DisplayName("Verify blocked_entities unique constraint in PostgreSQL")
    void shouldEnforceBlockedEntitiesConstraints() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        String entityValue = "card-hash-test-" + UUID.randomUUID();

        jdbcTemplate.update("""
            INSERT INTO blocked_entities (id, entity_type, entity_value, reason, blocked_at, is_active)
            VALUES (?, 'PAYMENT_METHOD', ?, 'Stolen card reported', NOW(), true)
            """, id1, entityValue);

        // Duplicate entity_type + entity_value violates unique index/constraint
        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO blocked_entities (id, entity_type, entity_value, reason, blocked_at, is_active)
            VALUES (?, 'PAYMENT_METHOD', ?, 'Duplicate insert test', NOW(), true)
            """, id2, entityValue))
            .isInstanceOf(Exception.class)
            .hasMessageContaining("duplicate key value violates unique constraint");
    }

    @Test
    @DisplayName("Verify JSONB operations on fraud signals and outbox_events")
    void shouldSupportJsonbOperationsOnFraudEvents() {
        UUID eventId = UUID.randomUUID();
        String jsonPayload = """
            {"order_id": "ord-fraud-1", "risk_score": 0.85, "triggered_rules": ["HIGH_VELOCITY", "GEO_MISMATCH"]}
            """;

        jdbcTemplate.update("""
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, source_service, correlation_id, schema_version, sent)
            VALUES (?, 'FraudScore', 'score-1', 'FRAUD_FLAGGED', ?::jsonb, 'fraud-detection-service', 'corr-f-1', 'v1', false)
            """, eventId, jsonPayload);

        String orderId = jdbcTemplate.queryForObject(
            "SELECT payload->>'order_id' FROM outbox_events WHERE id = ?",
            String.class,
            eventId
        );
        assertThat(orderId).isEqualTo("ord-fraud-1");

        Boolean hasRule = jdbcTemplate.queryForObject(
            "SELECT payload->'triggered_rules' ?? 'HIGH_VELOCITY' FROM outbox_events WHERE id = ?",
            Boolean.class,
            eventId
        );
        assertThat(hasRule).isTrue();
    }
}
