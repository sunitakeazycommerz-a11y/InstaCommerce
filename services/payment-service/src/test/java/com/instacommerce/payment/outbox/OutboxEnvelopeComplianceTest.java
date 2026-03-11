package com.instacommerce.payment.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.payment.domain.model.OutboxEvent;
import com.instacommerce.payment.repository.OutboxEventRepository;
import com.instacommerce.payment.service.OutboxService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

/**
 * Validates that outbox events comply with the standard event envelope
 * defined in contracts/README.md (Wave 16 Lane C).
 */
@ExtendWith(MockitoExtension.class)
class OutboxEnvelopeComplianceTest {

    @Mock
    OutboxEventRepository outboxEventRepository;

    @Captor
    ArgumentCaptor<OutboxEvent> eventCaptor;

    OutboxService outboxService;
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        outboxService = new OutboxService(outboxEventRepository, objectMapper);
        MDC.clear();
    }

    // ---- Entity-level @PrePersist tests ----

    @Nested
    @DisplayName("OutboxEvent entity defaults")
    class EntityDefaults {

        @Test
        @DisplayName("has eventId when created")
        void outboxEvent_hasEventId_whenCreated() {
            OutboxEvent event = new OutboxEvent();
            event.prePersist();

            assertThat(event.getEventId()).isNotNull();
            assertThat(event.getEventId()).isInstanceOf(UUID.class);
        }

        @Test
        @DisplayName("has schemaVersion default value")
        void outboxEvent_hasSchemaVersion_defaultValue() {
            OutboxEvent event = new OutboxEvent();
            event.prePersist();

            assertThat(event.getSchemaVersion()).isEqualTo("1.0");
        }

        @Test
        @DisplayName("has sourceService default value")
        void outboxEvent_hasSourceService_defaultValue() {
            OutboxEvent event = new OutboxEvent();
            event.prePersist();

            assertThat(event.getSourceService()).isEqualTo("payment-service");
        }

        @Test
        @DisplayName("has eventTimestamp when created")
        void outboxEvent_hasEventTimestamp_whenCreated() {
            Instant before = Instant.now();
            OutboxEvent event = new OutboxEvent();
            event.prePersist();
            Instant after = Instant.now();

            assertThat(event.getEventTimestamp()).isNotNull();
            assertThat(event.getEventTimestamp()).isBetween(before, after);
        }

        @Test
        @DisplayName("prePersist does not overwrite explicitly set values")
        void outboxEvent_prePersist_doesNotOverwriteExplicitValues() {
            UUID explicitId = UUID.randomUUID();
            Instant explicitTs = Instant.parse("2024-01-01T00:00:00Z");

            OutboxEvent event = new OutboxEvent();
            event.setEventId(explicitId);
            event.setSchemaVersion("2.0");
            event.setSourceService("other-service");
            event.setEventTimestamp(explicitTs);
            event.prePersist();

            assertThat(event.getEventId()).isEqualTo(explicitId);
            assertThat(event.getSchemaVersion()).isEqualTo("2.0");
            assertThat(event.getSourceService()).isEqualTo("other-service");
            assertThat(event.getEventTimestamp()).isEqualTo(explicitTs);
        }

        @Test
        @DisplayName("each call generates a unique eventId")
        void outboxEvent_uniqueEventIds() {
            OutboxEvent event1 = new OutboxEvent();
            event1.prePersist();

            OutboxEvent event2 = new OutboxEvent();
            event2.prePersist();

            assertThat(event1.getEventId()).isNotEqualTo(event2.getEventId());
        }
    }

    // ---- OutboxService publish tests ----

    @Nested
    @DisplayName("OutboxService.publish() envelope compliance")
    class PublishTests {

        @Test
        @DisplayName("correlationId from explicit param")
        void outboxEvent_correlationId_fromExplicitParam() {
            when(outboxEventRepository.save(any(OutboxEvent.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            outboxService.publish("Payment", "pay-123", "PaymentAuthorized",
                    Map.of("amount", 1000), "corr-explicit-456");

            verify(outboxEventRepository).save(eventCaptor.capture());
            OutboxEvent saved = eventCaptor.getValue();
            assertThat(saved.getCorrelationId()).isEqualTo("corr-explicit-456");
        }

        @Test
        @DisplayName("correlationId from MDC 'correlationId' when not explicit")
        void outboxEvent_correlationId_fromMDC_whenNotExplicit() {
            MDC.put("correlationId", "mdc-corr-789");
            when(outboxEventRepository.save(any(OutboxEvent.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            outboxService.publish("Payment", "pay-123", "PaymentAuthorized",
                    Map.of("amount", 1000), null);

            verify(outboxEventRepository).save(eventCaptor.capture());
            OutboxEvent saved = eventCaptor.getValue();
            assertThat(saved.getCorrelationId()).isEqualTo("mdc-corr-789");
        }

        @Test
        @DisplayName("correlationId from MDC 'X-Correlation-ID' as secondary fallback")
        void outboxEvent_correlationId_fromMDC_xCorrelationId() {
            MDC.put("X-Correlation-ID", "x-corr-abc");
            when(outboxEventRepository.save(any(OutboxEvent.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            outboxService.publish("Payment", "pay-123", "PaymentAuthorized",
                    Map.of("amount", 1000), null);

            verify(outboxEventRepository).save(eventCaptor.capture());
            OutboxEvent saved = eventCaptor.getValue();
            assertThat(saved.getCorrelationId()).isEqualTo("x-corr-abc");
        }

        @Test
        @DisplayName("correlationId is null when nothing available")
        void outboxEvent_correlationId_nullWhenNothingAvailable() {
            when(outboxEventRepository.save(any(OutboxEvent.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            outboxService.publish("Payment", "pay-123", "PaymentAuthorized",
                    Map.of("amount", 1000), null);

            verify(outboxEventRepository).save(eventCaptor.capture());
            OutboxEvent saved = eventCaptor.getValue();
            assertThat(saved.getCorrelationId()).isNull();
        }

        @Test
        @DisplayName("backward compatibility — existing 4-arg publish still works")
        void outboxEvent_backwardCompatibility_existingPublishStillWorks() {
            when(outboxEventRepository.save(any(OutboxEvent.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // This is the original 4-argument signature used by all existing call sites
            outboxService.publish("Payment", "pay-456", "PaymentCaptured",
                    Map.of("orderId", "ord-1", "amountCents", 5000));

            verify(outboxEventRepository).save(eventCaptor.capture());
            OutboxEvent saved = eventCaptor.getValue();
            assertThat(saved.getAggregateType()).isEqualTo("Payment");
            assertThat(saved.getAggregateId()).isEqualTo("pay-456");
            assertThat(saved.getEventType()).isEqualTo("PaymentCaptured");
            assertThat(saved.getPayload()).contains("orderId");
        }

        @Test
        @DisplayName("all envelope fields present in persisted event")
        void outboxEvent_allEnvelopeFieldsPresent_inPersistedEvent() {
            MDC.put("correlationId", "trace-999");
            when(outboxEventRepository.save(any(OutboxEvent.class)))
                    .thenAnswer(inv -> {
                        OutboxEvent e = inv.getArgument(0);
                        // simulate JPA @PrePersist
                        e.prePersist();
                        return e;
                    });

            outboxService.publish("Payment", "pay-789", "PaymentRefunded",
                    Map.of("refundId", "ref-1", "amountCents", 2500));

            verify(outboxEventRepository).save(eventCaptor.capture());
            OutboxEvent saved = eventCaptor.getValue();

            // Original fields
            assertThat(saved.getAggregateType()).isEqualTo("Payment");
            assertThat(saved.getAggregateId()).isEqualTo("pay-789");
            assertThat(saved.getEventType()).isEqualTo("PaymentRefunded");
            assertThat(saved.getPayload()).isNotBlank();

            // Envelope fields per contracts/README.md
            assertThat(saved.getEventId()).isNotNull();
            assertThat(saved.getSchemaVersion()).isEqualTo("1.0");
            assertThat(saved.getSourceService()).isEqualTo("payment-service");
            assertThat(saved.getCorrelationId()).isEqualTo("trace-999");
            assertThat(saved.getEventTimestamp()).isNotNull();
        }

        @Test
        @DisplayName("explicit correlationId takes priority over MDC")
        void outboxEvent_explicitCorrelationId_overridesMDC() {
            MDC.put("correlationId", "mdc-should-lose");
            when(outboxEventRepository.save(any(OutboxEvent.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            outboxService.publish("Payment", "pay-123", "PaymentVoided",
                    Map.of("reason", "cancelled"), "explicit-wins");

            verify(outboxEventRepository).save(eventCaptor.capture());
            assertThat(eventCaptor.getValue().getCorrelationId()).isEqualTo("explicit-wins");
        }
    }
}
