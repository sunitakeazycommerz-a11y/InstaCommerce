package com.instacommerce.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.audit.domain.model.AuditEvent;
import com.instacommerce.audit.dto.AuditEventRequest;
import com.instacommerce.audit.dto.AuditEventResponse;
import com.instacommerce.audit.repository.AuditEventRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditIngestionServiceTest {

    @Mock
    private AuditEventRepository repository;

    @Mock
    private HashChainService hashChainService;

    private AuditIngestionService ingestionService;

    @BeforeEach
    void setUp() {
        ingestionService = new AuditIngestionService(repository, hashChainService);
    }

    @Test
    @DisplayName("ingest successfully records an event with sequence and hash chain")
    void ingest_success() {
        UUID actorId = UUID.randomUUID();
        AuditEventRequest request = new AuditEventRequest(
            "ORDER_COMPLETED",
            "order-service",
            actorId,
            "USER",
            "ORDER",
            "order-999",
            "COMPLETE",
            Map.of("amount", 2999),
            "192.168.1.1",
            "Mozilla/5.0",
            "corr-abc-123"
        );

        when(hashChainService.getLatestHash()).thenReturn("prev-hash-111");
        when(hashChainService.nextSequenceNumber()).thenReturn(10L);
        when(hashChainService.computeEventHash(any(AuditEvent.class))).thenReturn("computed-hash-222");
        when(repository.save(any(AuditEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuditEventResponse response = ingestionService.ingest(request);

        assertThat(response).isNotNull();
        assertThat(response.sequenceNumber()).isEqualTo(10L);
        assertThat(response.previousHash()).isEqualTo("prev-hash-111");
        assertThat(response.eventHash()).isEqualTo("computed-hash-222");
        assertThat(response.eventType()).isEqualTo("ORDER_COMPLETED");
        assertThat(response.action()).isEqualTo("COMPLETE");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(captor.capture());
        AuditEvent saved = captor.getValue();
        assertThat(saved.getEventHash()).isEqualTo("computed-hash-222");
        assertThat(saved.getPreviousHash()).isEqualTo("prev-hash-111");
        assertThat(saved.getSequenceNumber()).isEqualTo(10L);
    }

    @Test
    @DisplayName("ingestBatch sequentially chains hashes for all items in batch")
    void ingestBatch_chainsSequentialHashes() {
        AuditEventRequest req1 = new AuditEventRequest(
            "PAYMENT_AUTHORIZED",
            "payment-service",
            UUID.randomUUID(),
            "USER",
            "PAYMENT",
            "pay-1",
            "AUTH",
            Map.of(),
            "127.0.0.1",
            "curl",
            "corr-1"
        );

        AuditEventRequest req2 = new AuditEventRequest(
            "PAYMENT_CAPTURED",
            "payment-service",
            UUID.randomUUID(),
            "SYSTEM",
            "PAYMENT",
            "pay-1",
            "CAPTURE",
            Map.of(),
            "127.0.0.1",
            "system-job",
            "corr-2"
        );

        when(hashChainService.getLatestHash()).thenReturn("genesis-hash");
        when(hashChainService.nextSequenceNumber()).thenReturn(1L, 2L);
        when(hashChainService.computeEventHash(any(AuditEvent.class)))
            .thenReturn("hash-1")
            .thenReturn("hash-2");
        when(repository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ingestionService.ingestBatch(List.of(req1, req2));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuditEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository, times(1)).saveAll(captor.capture());
        List<AuditEvent> savedEvents = captor.getValue();

        assertThat(savedEvents).hasSize(2);
        assertThat(savedEvents.get(0).getPreviousHash()).isEqualTo("genesis-hash");
        assertThat(savedEvents.get(0).getEventHash()).isEqualTo("hash-1");
        assertThat(savedEvents.get(1).getPreviousHash()).isEqualTo("hash-1");
        assertThat(savedEvents.get(1).getEventHash()).isEqualTo("hash-2");
    }
}
