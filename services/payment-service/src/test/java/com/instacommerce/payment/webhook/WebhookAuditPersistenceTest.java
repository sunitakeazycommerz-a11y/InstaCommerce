package com.instacommerce.payment.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.domain.model.ProcessedWebhookEvent;
import com.instacommerce.payment.repository.LedgerEntryRepository;
import com.instacommerce.payment.repository.PaymentRepository;
import com.instacommerce.payment.repository.ProcessedWebhookEventRepository;
import com.instacommerce.payment.repository.RefundRepository;
import com.instacommerce.payment.service.AuditLogService;
import com.instacommerce.payment.service.LedgerService;
import com.instacommerce.payment.service.OutboxService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Focused unit tests for Wave 9 webhook audit persistence:
 * verifying that eventType and rawPayload flow end-to-end from
 * the handler into the dedup marker persisted by the processor.
 */
@ExtendWith(MockitoExtension.class)
class WebhookAuditPersistenceTest {

    private static final String PSP_REF = "pi_audit_test_001";
    private static final String EVENT_ID = "evt_audit_test_001";
    private static final String EVENT_TYPE = "payment_intent.succeeded";

    @Mock PaymentRepository paymentRepository;
    @Mock ProcessedWebhookEventRepository processedWebhookEventRepository;
    @Mock RefundRepository refundRepository;
    @Mock LedgerEntryRepository ledgerEntryRepository;
    @Mock LedgerService ledgerService;
    @Mock OutboxService outboxService;
    @Mock AuditLogService auditLogService;
    @Mock EntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SimpleMeterRegistry meterRegistry;
    private WebhookEventProcessor processor;
    private WebhookEventHandler handler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        processor = new WebhookEventProcessor(
            paymentRepository, processedWebhookEventRepository, refundRepository,
            ledgerEntryRepository, ledgerService, outboxService, auditLogService, meterRegistry, false, false);
        ReflectionTestUtils.setField(processor, "entityManager", entityManager);
        handler = new WebhookEventHandler(
            objectMapper, processedWebhookEventRepository, processor);
    }

    // ── helpers ──────────────────────────────────────────────────────

    private String capturePayload(long amountCents) {
        return """
            {
              "id": "%s",
              "type": "%s",
              "data": {
                "object": {
                  "id": "%s",
                  "amount_received": %d
                }
              }
            }""".formatted(EVENT_ID, EVENT_TYPE, PSP_REF, amountCents);
    }

    private Payment authorizedPayment(long amountCents) {
        Payment p = new Payment();
        p.setId(UUID.randomUUID());
        p.setOrderId(UUID.randomUUID());
        p.setPspReference(PSP_REF);
        p.setAmountCents(amountCents);
        p.setCapturedCents(0);
        p.setRefundedCents(0);
        p.setCurrency("USD");
        p.setStatus(PaymentStatus.AUTHORIZED);
        return p;
    }

    // ── test suites ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Handler → Processor raw-payload pass-through")
    class HandlerPassThrough {

        @Test
        @DisplayName("handle() forwards the original payload string to processEvent()")
        void handlerPassesOriginalPayloadToProcessor() {
            String payload = capturePayload(5000);
            Payment payment = authorizedPayment(5000);

            when(processedWebhookEventRepository.existsById(EVENT_ID)).thenReturn(false);
            when(paymentRepository.findByPspReferenceForUpdate(PSP_REF))
                .thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            handler.handle(payload);

            // The processor must have received & persisted the raw payload verbatim
            ArgumentCaptor<ProcessedWebhookEvent> captor =
                ArgumentCaptor.forClass(ProcessedWebhookEvent.class);
            verify(processedWebhookEventRepository).saveAndFlush(captor.capture());

            ProcessedWebhookEvent persisted = captor.getValue();
            assertThat(persisted.getRawPayload()).isEqualTo(payload);
        }

        @Test
        @DisplayName("handle() forwards the event type string to processEvent()")
        void handlerPassesEventTypeToProcessor() {
            String payload = capturePayload(3000);
            Payment payment = authorizedPayment(3000);

            when(processedWebhookEventRepository.existsById(EVENT_ID)).thenReturn(false);
            when(paymentRepository.findByPspReferenceForUpdate(PSP_REF))
                .thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            handler.handle(payload);

            ArgumentCaptor<ProcessedWebhookEvent> captor =
                ArgumentCaptor.forClass(ProcessedWebhookEvent.class);
            verify(processedWebhookEventRepository).saveAndFlush(captor.capture());

            assertThat(captor.getValue().getEventType()).isEqualTo(EVENT_TYPE);
        }
    }

    @Nested
    @DisplayName("Processor dedup-marker audit fields")
    class ProcessorDedupMarker {

        @Test
        @DisplayName("processEvent() persists eventType on the dedup marker")
        void dedupMarkerContainsEventType() {
            ObjectNode objectNode = objectMapper.createObjectNode();
            objectNode.put("id", PSP_REF);
            objectNode.put("amount_received", 4200);
            Payment payment = authorizedPayment(4200);

            when(paymentRepository.findByPspReferenceForUpdate(PSP_REF))
                .thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            String rawPayload = "{\"id\":\"evt_audit_test_001\",\"type\":\"payment_intent.succeeded\"}";
            processor.processEvent(EVENT_ID, EVENT_TYPE, PSP_REF, objectNode, rawPayload);

            ArgumentCaptor<ProcessedWebhookEvent> captor =
                ArgumentCaptor.forClass(ProcessedWebhookEvent.class);
            verify(processedWebhookEventRepository).saveAndFlush(captor.capture());

            ProcessedWebhookEvent marker = captor.getValue();
            assertThat(marker.getEventId()).isEqualTo(EVENT_ID);
            assertThat(marker.getEventType()).isEqualTo(EVENT_TYPE);
        }

        @Test
        @DisplayName("processEvent() persists rawPayload on the dedup marker")
        void dedupMarkerContainsRawPayload() {
            ObjectNode objectNode = objectMapper.createObjectNode();
            objectNode.put("id", PSP_REF);
            objectNode.put("amount_received", 7500);
            Payment payment = authorizedPayment(7500);

            when(paymentRepository.findByPspReferenceForUpdate(PSP_REF))
                .thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            String rawPayload = capturePayload(7500);
            processor.processEvent(EVENT_ID, EVENT_TYPE, PSP_REF, objectNode, rawPayload);

            ArgumentCaptor<ProcessedWebhookEvent> captor =
                ArgumentCaptor.forClass(ProcessedWebhookEvent.class);
            verify(processedWebhookEventRepository).saveAndFlush(captor.capture());

            assertThat(captor.getValue().getRawPayload()).isEqualTo(rawPayload);
        }

        @Test
        @DisplayName("processEvent() sets all three key fields together on the marker")
        void dedupMarkerContainsAllAuditFields() {
            ObjectNode objectNode = objectMapper.createObjectNode();
            objectNode.put("id", PSP_REF);
            objectNode.put("amount_received", 1000);
            Payment payment = authorizedPayment(1000);

            when(paymentRepository.findByPspReferenceForUpdate(PSP_REF))
                .thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            String rawPayload = capturePayload(1000);
            processor.processEvent(EVENT_ID, EVENT_TYPE, PSP_REF, objectNode, rawPayload);

            ArgumentCaptor<ProcessedWebhookEvent> captor =
                ArgumentCaptor.forClass(ProcessedWebhookEvent.class);
            verify(processedWebhookEventRepository).saveAndFlush(captor.capture());

            ProcessedWebhookEvent marker = captor.getValue();
            assertThat(marker.getEventId()).isEqualTo(EVENT_ID);
            assertThat(marker.getEventType()).isEqualTo(EVENT_TYPE);
            assertThat(marker.getRawPayload()).isEqualTo(rawPayload);
        }
    }

    @Nested
    @DisplayName("Duplicate dedup-marker short-circuit")
    class DuplicateShortCircuit {

        @Test
        @DisplayName("DataIntegrityViolation on saveAndFlush short-circuits without applying event logic")
        void duplicateDedupMarkerSkipsEventProcessing() {
            ObjectNode objectNode = objectMapper.createObjectNode();
            objectNode.put("id", PSP_REF);
            objectNode.put("amount_received", 5000);

            when(processedWebhookEventRepository.saveAndFlush(any(ProcessedWebhookEvent.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

            String rawPayload = capturePayload(5000);
            processor.processEvent(EVENT_ID, EVENT_TYPE, PSP_REF, objectNode, rawPayload);

            // No payment lookup, no ledger, no outbox
            verifyNoInteractions(paymentRepository);
            verifyNoInteractions(ledgerService);
            verifyNoInteractions(outboxService);
        }

        @Test
        @DisplayName("Duplicate still attempts to persist with correct audit fields before short-circuiting")
        void duplicateAttemptIncludesAuditFields() {
            ObjectNode objectNode = objectMapper.createObjectNode();
            objectNode.put("id", PSP_REF);

            when(processedWebhookEventRepository.saveAndFlush(any(ProcessedWebhookEvent.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

            String rawPayload = "{\"duplicate\":true}";
            processor.processEvent(EVENT_ID, "charge.refunded", PSP_REF, objectNode, rawPayload);

            ArgumentCaptor<ProcessedWebhookEvent> captor =
                ArgumentCaptor.forClass(ProcessedWebhookEvent.class);
            verify(processedWebhookEventRepository).saveAndFlush(captor.capture());

            ProcessedWebhookEvent attempted = captor.getValue();
            assertThat(attempted.getEventId()).isEqualTo(EVENT_ID);
            assertThat(attempted.getEventType()).isEqualTo("charge.refunded");
            assertThat(attempted.getRawPayload()).isEqualTo(rawPayload);
        }

        @Test
        @DisplayName("Null/blank eventId skips dedup marker entirely and proceeds to event logic")
        void nullEventIdSkipsDedupMarker() {
            ObjectNode objectNode = objectMapper.createObjectNode();
            objectNode.put("id", PSP_REF);
            objectNode.put("amount_received", 2000);
            Payment payment = authorizedPayment(2000);

            when(paymentRepository.findByPspReferenceForUpdate(PSP_REF))
                .thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            processor.processEvent(null, EVENT_TYPE, PSP_REF, objectNode, capturePayload(2000));

            // Dedup marker never saved, but event logic proceeds
            verify(processedWebhookEventRepository, never()).saveAndFlush(any());
            verify(paymentRepository).findByPspReferenceForUpdate(PSP_REF);
        }
    }
}
