package com.instacommerce.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.instacommerce.audit.controller.AuditIntegrityController.IntegrityReport;
import com.instacommerce.audit.domain.model.AuditEvent;
import com.instacommerce.audit.repository.AuditArchiveManifestRepository;
import com.instacommerce.audit.repository.AuditEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class AuditIntegrityServiceTest {

    private static final Instant BASE_TIME = Instant.parse("2026-01-15T10:00:00Z");

    @Mock
    private AuditEventRepository repository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private AuditArchiveManifestRepository manifestRepository;

    private HashChainService hashChainService;
    private AuditIntegrityService integrityService;

    @BeforeEach
    void setUp() {
        hashChainService = new HashChainService(jdbcTemplate);
        integrityService = new AuditIntegrityService(repository, hashChainService, manifestRepository);
    }

    @Test
    void verifyChain_intactFromGenesis_returnsIntact() {
        AuditEvent e1 = buildChainedEvent(1L, hashChainService.genesisHash(), BASE_TIME);
        AuditEvent e2 = buildChainedEvent(2L, e1.getEventHash(), BASE_TIME.plusSeconds(1));
        AuditEvent e3 = buildChainedEvent(3L, e2.getEventHash(), BASE_TIME.plusSeconds(2));

        when(repository.findChainedEvents(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of(e1, e2, e3))
                .thenReturn(List.of());

        IntegrityReport report = integrityService.verifyChain(null, null, 10);

        assertThat(report.intact()).isTrue();
        assertThat(report.eventsVerified()).isEqualTo(3);
        assertThat(report.chainBreaks()).isZero();
        assertThat(report.firstBreakSequence()).isNull();
    }

    @Test
    void verifyChain_multiPageIntact_carriesExpectedHashAcrossPages() {
        AuditEvent e1 = buildChainedEvent(1L, hashChainService.genesisHash(), BASE_TIME);
        AuditEvent e2 = buildChainedEvent(2L, e1.getEventHash(), BASE_TIME.plusSeconds(1));
        AuditEvent e3 = buildChainedEvent(3L, e2.getEventHash(), BASE_TIME.plusSeconds(2));
        AuditEvent e4 = buildChainedEvent(4L, e3.getEventHash(), BASE_TIME.plusSeconds(3));

        when(repository.findChainedEvents(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of(e1, e2))
                .thenReturn(List.of(e3, e4))
                .thenReturn(List.of());

        IntegrityReport report = integrityService.verifyChain(null, null, 2);

        assertThat(report.intact()).isTrue();
        assertThat(report.eventsVerified()).isEqualTo(4);
        assertThat(report.chainBreaks()).isZero();
        assertThat(report.firstBreakSequence()).isNull();
    }

    @Test
    void verifyChain_tamperedEventHash_detectsBreak() {
        AuditEvent e1 = buildChainedEvent(1L, hashChainService.genesisHash(), BASE_TIME);
        AuditEvent e2 = buildEventWithExplicitHash(2L, e1.getEventHash(), "tampered-event-hash", BASE_TIME.plusSeconds(1));
        AuditEvent e3 = buildChainedEvent(3L, e2.getEventHash(), BASE_TIME.plusSeconds(2));

        when(repository.findChainedEvents(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of(e1, e2, e3))
                .thenReturn(List.of());

        IntegrityReport report = integrityService.verifyChain(null, null, 10);

        assertThat(report.intact()).isFalse();
        assertThat(report.eventsVerified()).isEqualTo(3);
        assertThat(report.chainBreaks()).isEqualTo(1);
        assertThat(report.firstBreakSequence()).isEqualTo("2");
    }

    @Test
    void verifyChain_tamperedPreviousHashWithRecomputedEventHash_detectsBreak() {
        AuditEvent e1 = buildChainedEvent(1L, hashChainService.genesisHash(), BASE_TIME);
        AuditEvent e2 = buildChainedEvent(2L, "tampered-previous-hash", BASE_TIME.plusSeconds(1));
        AuditEvent e3 = buildChainedEvent(3L, e2.getEventHash(), BASE_TIME.plusSeconds(2));

        when(repository.findChainedEvents(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of(e1, e2, e3))
                .thenReturn(List.of());

        IntegrityReport report = integrityService.verifyChain(null, null, 10);

        assertThat(report.intact()).isFalse();
        assertThat(report.eventsVerified()).isEqualTo(3);
        assertThat(report.chainBreaks()).isEqualTo(1);
        assertThat(report.firstBreakSequence()).isEqualTo("2");
    }

    @Test
    void verifyChain_deletedMiddleEvent_detectsBrokenLink() {
        AuditEvent e1 = buildChainedEvent(1L, hashChainService.genesisHash(), BASE_TIME);
        AuditEvent e2 = buildChainedEvent(2L, e1.getEventHash(), BASE_TIME.plusSeconds(1));
        AuditEvent e3 = buildChainedEvent(3L, e2.getEventHash(), BASE_TIME.plusSeconds(2));

        when(repository.findChainedEvents(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of(e1, e3))
                .thenReturn(List.of());

        IntegrityReport report = integrityService.verifyChain(null, null, 10);

        assertThat(report.intact()).isFalse();
        assertThat(report.eventsVerified()).isEqualTo(2);
        assertThat(report.chainBreaks()).isEqualTo(1);
        assertThat(report.firstBreakSequence()).isEqualTo("3");
    }

    @Test
    void verifyChain_seq1WrongGenesisPreviousHashWithRecomputedHash_detectsBreak() {
        AuditEvent e1 = buildChainedEvent(1L, "wrong-genesis-hash", BASE_TIME);
        AuditEvent e2 = buildChainedEvent(2L, e1.getEventHash(), BASE_TIME.plusSeconds(1));

        when(repository.findChainedEvents(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of(e1, e2))
                .thenReturn(List.of());

        IntegrityReport report = integrityService.verifyChain(null, null, 10);

        assertThat(report.intact()).isFalse();
        assertThat(report.eventsVerified()).isEqualTo(2);
        assertThat(report.chainBreaks()).isEqualTo(1);
        assertThat(report.firstBreakSequence()).isEqualTo("1");
    }

    @Test
    void verifyChain_crossPageContinuityBreak_detectsBreak() {
        AuditEvent e1 = buildChainedEvent(1L, hashChainService.genesisHash(), BASE_TIME);
        AuditEvent e2 = buildChainedEvent(2L, e1.getEventHash(), BASE_TIME.plusSeconds(1));
        AuditEvent e3 = buildChainedEvent(3L, e2.getEventHash(), BASE_TIME.plusSeconds(2));
        AuditEvent e4 = buildChainedEvent(4L, e3.getEventHash(), BASE_TIME.plusSeconds(3));

        when(repository.findChainedEvents(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of(e1, e2))
                .thenReturn(List.of(e4))
                .thenReturn(List.of());

        IntegrityReport report = integrityService.verifyChain(null, null, 2);

        assertThat(report.intact()).isFalse();
        assertThat(report.eventsVerified()).isEqualTo(3);
        assertThat(report.chainBreaks()).isEqualTo(1);
        assertThat(report.firstBreakSequence()).isEqualTo("4");
    }

    @Test
    void verifyChain_rolledBackSequenceGapWithIntactHashLink_returnsIntact() {
        AuditEvent e1 = buildChainedEvent(1L, hashChainService.genesisHash(), BASE_TIME);
        AuditEvent e3 = buildChainedEvent(3L, e1.getEventHash(), BASE_TIME.plusSeconds(2));

        when(repository.findChainedEvents(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of(e1, e3))
                .thenReturn(List.of());

        IntegrityReport report = integrityService.verifyChain(null, null, 10);

        assertThat(report.intact()).isTrue();
        assertThat(report.eventsVerified()).isEqualTo(2);
        assertThat(report.chainBreaks()).isZero();
    }

    @Test
    void verifyChain_emptyResult_returnsIntactZeroEvents() {
        when(repository.findChainedEvents(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of());

        IntegrityReport report = integrityService.verifyChain(null, null, 10);

        assertThat(report.intact()).isTrue();
        assertThat(report.eventsVerified()).isZero();
        assertThat(report.chainBreaks()).isZero();
        assertThat(report.firstBreakSequence()).isNull();
    }

    @Test
    void verifyChain_windowedWithMatchingPredecessor_returnsIntact() {
        AuditEvent e1 = buildChainedEvent(1L, hashChainService.genesisHash(), BASE_TIME);
        AuditEvent e2 = buildChainedEvent(2L, e1.getEventHash(), BASE_TIME.plusSeconds(10));
        AuditEvent e3 = buildChainedEvent(3L, e2.getEventHash(), BASE_TIME.plusSeconds(20));

        Instant from = BASE_TIME.plusSeconds(10);
        Instant to = BASE_TIME.plusSeconds(30);
        when(repository.findChainedEvents(eq(from), eq(to), any(Pageable.class)))
                .thenReturn(List.of(e2, e3))
                .thenReturn(List.of());
        when(repository.findFirstBySequenceNumberLessThanOrderBySequenceNumberDesc(2L))
                .thenReturn(Optional.of(e1));

        IntegrityReport report = integrityService.verifyChain(from, to, 10);

        assertThat(report.intact()).isTrue();
        assertThat(report.eventsVerified()).isEqualTo(2);
        assertThat(report.chainBreaks()).isZero();
    }

    @Test
    void verifyChain_windowedWithPredecessorMismatch_detectsBreak() {
        AuditEvent e1 = buildChainedEvent(1L, hashChainService.genesisHash(), BASE_TIME);
        AuditEvent e2 = buildChainedEvent(2L, "forged-prev-hash", BASE_TIME.plusSeconds(10));
        AuditEvent e3 = buildChainedEvent(3L, e2.getEventHash(), BASE_TIME.plusSeconds(20));

        Instant from = BASE_TIME.plusSeconds(10);
        when(repository.findChainedEvents(eq(from), isNull(), any(Pageable.class)))
                .thenReturn(List.of(e2, e3))
                .thenReturn(List.of());
        when(repository.findFirstBySequenceNumberLessThanOrderBySequenceNumberDesc(2L))
                .thenReturn(Optional.of(e1));

        IntegrityReport report = integrityService.verifyChain(from, null, 10);

        assertThat(report.intact()).isFalse();
        assertThat(report.eventsVerified()).isEqualTo(2);
        assertThat(report.chainBreaks()).isEqualTo(1);
        assertThat(report.firstBreakSequence()).isEqualTo("2");
    }

    @Test
    void verifyChain_windowedWithoutLocalPredecessor_verifiesHashesAndInnerLinks() {
        AuditEvent e10 = buildChainedEvent(10L, "archived-partition-hash", BASE_TIME);
        AuditEvent e11 = buildChainedEvent(11L, e10.getEventHash(), BASE_TIME.plusSeconds(10));

        Instant from = BASE_TIME;
        when(repository.findChainedEvents(eq(from), isNull(), any(Pageable.class)))
                .thenReturn(List.of(e10, e11))
                .thenReturn(List.of());
        when(repository.findFirstBySequenceNumberLessThanOrderBySequenceNumberDesc(10L))
                .thenReturn(Optional.empty());

        IntegrityReport report = integrityService.verifyChain(from, null, 10);

        assertThat(report.intact()).isTrue();
        assertThat(report.eventsVerified()).isEqualTo(2);
        assertThat(report.chainBreaks()).isZero();
    }

    @Test
    void verifyChain_unarchivedMissingPrefix_detectsBreak() {
        AuditEvent e10 = buildChainedEvent(10L, "missing-prefix-hash", BASE_TIME);
        AuditEvent e11 = buildChainedEvent(11L, e10.getEventHash(), BASE_TIME.plusSeconds(10));

        when(repository.findChainedEvents(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of(e10, e11))
                .thenReturn(List.of());
        when(manifestRepository.existsByVerificationStatus("VERIFIED")).thenReturn(false);

        IntegrityReport report = integrityService.verifyChain(null, null, 10);

        assertThat(report.intact()).isFalse();
        assertThat(report.eventsVerified()).isEqualTo(2);
        assertThat(report.chainBreaks()).isEqualTo(1);
        assertThat(report.firstBreakSequence()).isEqualTo("10");
    }

    @Test
    void verifyChain_afterVerifiedArchiveDetach_returnsIntact() {
        AuditEvent e10 = buildChainedEvent(10L, "archived-partition-hash", BASE_TIME);
        AuditEvent e11 = buildChainedEvent(11L, e10.getEventHash(), BASE_TIME.plusSeconds(10));

        when(repository.findChainedEvents(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of(e10, e11))
                .thenReturn(List.of());
        when(manifestRepository.existsByVerificationStatus("VERIFIED")).thenReturn(true);

        IntegrityReport report = integrityService.verifyChain(null, null, 10);

        assertThat(report.intact()).isTrue();
        assertThat(report.eventsVerified()).isEqualTo(2);
        assertThat(report.chainBreaks()).isZero();
        assertThat(report.firstBreakSequence()).isNull();
    }

    private AuditEvent buildChainedEvent(long seq, String previousHash, Instant createdAt) {
        AuditEvent unsigned = AuditEvent.builder()
                .eventType("ORDER_CREATED")
                .sourceService("order-service")
                .action("CREATE")
                .actorId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .resourceType("Order")
                .resourceId("ord-" + seq)
                .correlationId("corr-" + seq)
                .sequenceNumber(seq)
                .previousHash(previousHash)
                .createdAt(createdAt)
                .build();
        String computedHash = hashChainService.computeEventHash(unsigned);
        return buildEventWithExplicitHash(seq, previousHash, computedHash, createdAt);
    }

    private AuditEvent buildEventWithExplicitHash(long seq, String previousHash, String eventHash, Instant createdAt) {
        return AuditEvent.builder()
                .eventType("ORDER_CREATED")
                .sourceService("order-service")
                .action("CREATE")
                .actorId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .resourceType("Order")
                .resourceId("ord-" + seq)
                .correlationId("corr-" + seq)
                .sequenceNumber(seq)
                .previousHash(previousHash)
                .eventHash(eventHash)
                .createdAt(createdAt)
                .build();
    }
}
