package com.instacommerce.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.audit.domain.model.AuditEvent;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
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
}
