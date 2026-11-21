package com.instacommerce.riderfleet.integration;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Integration test verifying full Flyway migration execution (V1 to V11)
 * against real PostgreSQL 16 instance using Testcontainers for Rider Fleet Service.
 */
@Testcontainers(disabledWithoutDocker = true)
class RiderFleetPostgresFlywayIntegrationTest {

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
        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(11);
    }

    @Test
    @DisplayName("Verify Flyway migrations created rider fleet public tables")
    void shouldCreateRiderFleetTables() {
        List<String> tables = jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name",
            String.class
        );

        assertThat(tables).contains(
            "riders",
            "rider_shifts",
            "rider_availability",
            "rider_earnings",
            "rider_ratings",
            "outbox_events",
            "shedlock"
        );
    }

    @Test
    @DisplayName("Verify optimistic lock version column and constraints on riders")
    void shouldVerifyRiderVersionAndConstraints() {
        UUID riderId = UUID.randomUUID();
        String phone = "+919876543210";

        jdbcTemplate.update("""
            INSERT INTO riders (id, name, phone, email, vehicle_type, status, current_store_id, is_active, version)
            VALUES (?, 'Rider Test', ?, 'rider@instacommerce.com', 'BIKE', 'AVAILABLE', 'store-1', true, 0)
            """, riderId, phone);

        Integer version = jdbcTemplate.queryForObject(
            "SELECT version FROM riders WHERE id = ?",
            Integer.class,
            riderId
        );
        assertThat(version).isEqualTo(0);

        // Update with optimistic locking increment
        int rows = jdbcTemplate.update("""
            UPDATE riders SET status = 'BUSY', version = version + 1 WHERE id = ? AND version = ?
            """, riderId, 0);
        assertThat(rows).isEqualTo(1);

        // Verify updated version
        Integer newVersion = jdbcTemplate.queryForObject(
            "SELECT version FROM riders WHERE id = ?",
            Integer.class,
            riderId
        );
        assertThat(newVersion).isEqualTo(1);
    }

    @Test
    @DisplayName("Verify PostgreSQL JSONB operations on outbox_events in rider-fleet")
    void shouldSupportJsonbOperationsOnRiderOutbox() {
        UUID eventId = UUID.randomUUID();
        String jsonPayload = """
            {"rider_id": "rider-101", "order_id": "ord-202", "assigned_at": "2026-10-19T10:00:00Z"}
            """;

        jdbcTemplate.update("""
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, source_service, correlation_id, schema_version, sent)
            VALUES (?, 'Rider', 'rider-101', 'RiderAssigned', ?::jsonb, 'rider-fleet-service', 'corr-rf-1', 'v1', false)
            """, eventId, jsonPayload);

        String riderId = jdbcTemplate.queryForObject(
            "SELECT payload->>'rider_id' FROM outbox_events WHERE id = ?",
            String.class,
            eventId
        );
        assertThat(riderId).isEqualTo("rider-101");
    }

    @Test
    @DisplayName("Candidate eligibility query skips off-shift and fatigued nearest riders in PostgreSQL")
    void shouldFilterOffShiftAndFatiguedNearestRidersInPostgres() {
        UUID storeId = UUID.randomUUID();
        UUID offShiftNearest = UUID.randomUUID();
        UUID fatiguedSecond = UUID.randomUUID();
        UUID eligibleThird = UUID.randomUUID();

        jdbcTemplate.update("""
            INSERT INTO riders (id, name, phone, vehicle_type, status, rating_avg, store_id, consecutive_deliveries, last_delivery_completed_at, version)
            VALUES (?, 'Nearest Off-Shift', '+919900000001', 'MOTORCYCLE', 'ACTIVE', 5.00, ?, 0, NULL, 0),
                   (?, 'Second Fatigued', '+919900000002', 'MOTORCYCLE', 'ACTIVE', 4.90, ?, 8, now(), 0),
                   (?, 'Third Eligible', '+919900000003', 'MOTORCYCLE', 'ACTIVE', 4.80, ?, 1, now() - interval '2 hours', 0)
            """, offShiftNearest, storeId, fatiguedSecond, storeId, eligibleThird, storeId);

        jdbcTemplate.update("""
            INSERT INTO rider_availability (rider_id, is_available, current_lat, current_lng, store_id)
            VALUES (?, true, 12.97160000, 77.59460000, ?),
                   (?, true, 12.97200000, 77.59500000, ?),
                   (?, true, 12.97500000, 77.59800000, ?)
            """, offShiftNearest, storeId, fatiguedSecond, storeId, eligibleThird, storeId);

        jdbcTemplate.update("""
            INSERT INTO rider_shifts (rider_id, shift_start, shift_end, status)
            VALUES (?, now() - interval '1 hour', now() + interval '4 hours', 'ACTIVE'),
                   (?, now() - interval '1 hour', now() + interval '4 hours', 'ACTIVE')
            """, fatiguedSecond, eligibleThird);

        List<UUID> candidates = jdbcTemplate.queryForList("""
            SELECT ra.rider_id FROM rider_availability ra
            JOIN riders r ON r.id = ra.rider_id
            WHERE ra.is_available = true
              AND ra.store_id = ?
              AND r.status = 'ACTIVE'
              AND (r.consecutive_deliveries < 8
                   OR r.last_delivery_completed_at IS NULL
                   OR r.last_delivery_completed_at <= now() - interval '45 minutes')
              AND EXISTS (
                   SELECT 1 FROM rider_shifts rs
                   WHERE rs.rider_id = r.id
                     AND rs.status = 'ACTIVE'
                     AND rs.shift_start <= now()
                     AND rs.shift_end > now()
              )
            ORDER BY (6371 * acos(LEAST(1.0, GREATEST(-1.0,
                cos(radians(12.9716)) * cos(radians(ra.current_lat))
                * cos(radians(ra.current_lng) - radians(77.5946))
                + sin(radians(12.9716)) * sin(radians(ra.current_lat)))))) ASC
            LIMIT 20
            """, UUID.class, storeId);

        assertThat(candidates).containsExactly(eligibleThird);
    }
}
