package com.instacommerce.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.audit.domain.model.AuditEvent;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class HashChainServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private HashChainService hashChainService;

    @BeforeEach
    void setUp() {
        hashChainService = new HashChainService(jdbcTemplate);
    }

    @Test
    @DisplayName("sha256 computes expected deterministic hash")
    void sha256_computesDeterministicHash() {
        String hash1 = HashChainService.sha256("test-input");
        String hash2 = HashChainService.sha256("test-input");

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64);
    }

    @Test
    @DisplayName("getLatestHash returns genesis seed hash when audit_events table is empty")
    void getLatestHash_returnsGenesisSeed_whenTableEmpty() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class))).thenReturn(Collections.emptyList());

        String latestHash = hashChainService.getLatestHash();

        String expectedGenesisHash = HashChainService.sha256(HashChainService.GENESIS_SEED);
        assertThat(latestHash).isEqualTo(expectedGenesisHash);
    }

    @Test
    @DisplayName("getLatestHash returns latest event_hash when records exist")
    void getLatestHash_returnsLatestHash_whenRecordsExist() {
        String expectedHash = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90";
        when(jdbcTemplate.queryForList(anyString(), eq(String.class))).thenReturn(List.of(expectedHash));

        String latestHash = hashChainService.getLatestHash();

        assertThat(latestHash).isEqualTo(expectedHash);
    }

    @Test
    @DisplayName("nextSequenceNumber fetches next sequence value from database")
    void nextSequenceNumber_returnsNextval() {
        when(jdbcTemplate.queryForObject("SELECT nextval('audit_chain_seq')", Long.class)).thenReturn(42L);

        long seq = hashChainService.nextSequenceNumber();

        assertThat(seq).isEqualTo(42L);
        verify(jdbcTemplate).queryForObject("SELECT nextval('audit_chain_seq')", Long.class);
    }

    @Test
    @DisplayName("computeEventHash produces deterministic hash that alters if any field is tampered")
    void computeEventHash_tamperEvident() {
        UUID actorId = UUID.randomUUID();
        Instant now = Instant.parse("2026-03-01T10:00:00Z");

        AuditEvent original = AuditEvent.builder()
            .sequenceNumber(1L)
            .eventType("ORDER_CREATED")
            .sourceService("order-service")
            .action("CREATE")
            .actorId(actorId)
            .resourceType("ORDER")
            .resourceId("order-100")
            .correlationId("corr-123")
            .createdAt(now)
            .previousHash("0000000000000000000000000000000000000000000000000000000000000000")
            .build();

        String hash1 = hashChainService.computeEventHash(original);
        String hash2 = hashChainService.computeEventHash(original);
        assertThat(hash1).isEqualTo(hash2);

        // Tamper with sequence number
        AuditEvent tamperedSeq = AuditEvent.builder()
            .sequenceNumber(2L)
            .eventType(original.getEventType())
            .sourceService(original.getSourceService())
            .action(original.getAction())
            .actorId(original.getActorId())
            .resourceType(original.getResourceType())
            .resourceId(original.getResourceId())
            .correlationId(original.getCorrelationId())
            .createdAt(original.getCreatedAt())
            .previousHash(original.getPreviousHash())
            .build();

        String tamperedHash = hashChainService.computeEventHash(tamperedSeq);
        assertThat(tamperedHash).isNotEqualTo(hash1);
    }

    @Test
    @DisplayName("computeEventHash truncates createdAt to microseconds and detects tampering of extended fields")
    void computeEventHash_truncatesMicrosAndCoversAllAuditFields() {
        UUID eventId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Instant nanos = Instant.parse("2026-03-01T10:00:00.123456789Z");
        Instant micros = Instant.parse("2026-03-01T10:00:00.123456000Z");

        Map<String, Object> detailsA = new LinkedHashMap<>();
        detailsA.put("b", 2);
        detailsA.put("a", "alpha");

        Map<String, Object> detailsB = new LinkedHashMap<>();
        detailsB.put("a", "alpha");
        detailsB.put("b", 2);

        AuditEvent base = AuditEvent.builder()
            .id(eventId)
            .sequenceNumber(1L)
            .eventType("ORDER_CREATED")
            .sourceService("order-service")
            .action("CREATE")
            .actorId(actorId)
            .actorType("USER")
            .resourceType("ORDER")
            .resourceId("order-100")
            .details(detailsA)
            .ipAddress("10.0.0.1")
            .userAgent("Mozilla/5.0")
            .correlationId("corr-123")
            .createdAt(nanos)
            .previousHash("0000000000000000000000000000000000000000000000000000000000000000")
            .build();

        AuditEvent reorderedSameMicros = AuditEvent.builder()
            .id(eventId)
            .sequenceNumber(1L)
            .eventType("ORDER_CREATED")
            .sourceService("order-service")
            .action("CREATE")
            .actorId(actorId)
            .actorType("USER")
            .resourceType("ORDER")
            .resourceId("order-100")
            .details(detailsB)
            .ipAddress("10.0.0.1")
            .userAgent("Mozilla/5.0")
            .correlationId("corr-123")
            .createdAt(micros)
            .previousHash("0000000000000000000000000000000000000000000000000000000000000000")
            .build();

        String baseHash = hashChainService.computeEventHash(base);
        assertThat(hashChainService.computeEventHash(reorderedSameMicros)).isEqualTo(baseHash);

        AuditEvent tamperedIp = AuditEvent.builder()
            .id(eventId)
            .sequenceNumber(1L)
            .eventType("ORDER_CREATED")
            .sourceService("order-service")
            .action("CREATE")
            .actorId(actorId)
            .actorType("USER")
            .resourceType("ORDER")
            .resourceId("order-100")
            .details(detailsA)
            .ipAddress("10.0.0.2")
            .userAgent("Mozilla/5.0")
            .correlationId("corr-123")
            .createdAt(micros)
            .previousHash("0000000000000000000000000000000000000000000000000000000000000000")
            .build();

        assertThat(hashChainService.computeEventHash(tamperedIp)).isNotEqualTo(baseHash);
    }

    @Test
    @DisplayName("partitionBucket formats UTC monthly partition name")
    void partitionBucket_formatsUtcYearMonth() {
        assertThat(hashChainService.partitionBucket(Instant.parse("2026-11-30T23:59:59Z")))
                .isEqualTo("audit_events_2026_11");
        assertThat(hashChainService.partitionBucket(Instant.parse("2026-12-01T00:00:00Z")))
                .isEqualTo("audit_events_2026_12");
    }

    @Test
    @DisplayName("acquirePartitionLock acquires PostgreSQL transaction-scoped advisory lock keyed to partition")
    void acquirePartitionLock_executesPgAdvisoryXactLock() {
        String bucketA = "audit_events_2026_11";
        String bucketB = "audit_events_2026_12";

        long keyA1 = hashChainService.advisoryLockKey(bucketA);
        long keyA2 = hashChainService.advisoryLockKey(bucketA);
        long keyB = hashChainService.advisoryLockKey(bucketB);

        assertThat(keyA1).isEqualTo(keyA2).isNotEqualTo(keyB);

        hashChainService.acquirePartitionLock(bucketA);

        verify(jdbcTemplate).queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, keyA1);
    }

    @Test
    @DisplayName("acquireChainLock acquires single global advisory lock independent of partition bucket")
    void acquireChainLock_executesGlobalPgAdvisoryXactLock() {
        long globalKey = hashChainService.advisoryLockKey(HashChainService.GLOBAL_CHAIN_LOCK);

        hashChainService.acquireChainLock();

        verify(jdbcTemplate).queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, globalKey);
    }

    @Test
    @DisplayName("computeLegacyV1EventHash matches legacy 10-field SHA-256 formula and new events default to hashVersion 2")
    void computeLegacyV1EventHash_matchesLegacyTenFieldFormula() {
        UUID actorId = UUID.fromString("00000000-0000-0000-0000-000000000007");
        Instant ts = Instant.parse("2026-01-10T12:00:00Z");
        String prev = hashChainService.genesisHash();

        AuditEvent legacyEvent = AuditEvent.builder()
            .id(UUID.randomUUID())
            .sequenceNumber(1L)
            .eventType("ORDER_CREATED")
            .sourceService("order-service")
            .action("CREATE")
            .actorId(actorId)
            .actorType("USER")
            .resourceType("Order")
            .resourceId("ord-1")
            .details(Map.of("k", "v"))
            .ipAddress("10.0.0.1")
            .userAgent("agent")
            .correlationId("corr-1")
            .createdAt(ts)
            .previousHash(prev)
            .hashVersion(1)
            .build();

        String expectedV1 = HashChainService.sha256(
            "1|ORDER_CREATED|order-service|CREATE|" + actorId + "|Order|ord-1|corr-1|" + ts + "|" + prev);
        assertThat(hashChainService.computeLegacyV1EventHash(legacyEvent)).isEqualTo(expectedV1);
        assertThat(hashChainService.computeEventHash(legacyEvent)).isNotEqualTo(expectedV1);

        AuditEvent v2Event = AuditEvent.builder()
            .eventType("ORDER_CREATED")
            .sourceService("order-service")
            .action("CREATE")
            .build();
        assertThat(v2Event.getHashVersion()).isEqualTo(2);
    }
}
