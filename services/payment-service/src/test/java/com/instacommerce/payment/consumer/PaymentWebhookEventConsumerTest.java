package com.instacommerce.payment.consumer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.payment.consumer.WebhookKafkaBridge.Result;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link PaymentWebhookEventConsumer}.
 * <p>
 * Validates JSON deserialization → bridge delegation, skip-result handling,
 * and error propagation behaviour that the CommonErrorHandler depends on.
 */
@ExtendWith(MockitoExtension.class)
class PaymentWebhookEventConsumerTest {

    private static final String TOPIC = "payment.webhooks";

    /** Real ObjectMapper — we want to exercise actual deserialization. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private WebhookKafkaBridge bridge;

    private PaymentWebhookEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new PaymentWebhookEventConsumer(objectMapper, bridge);
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    private ConsumerRecord<String, String> record(String json) {
        return new ConsumerRecord<>(TOPIC, 0, 42L, "key-1", json);
    }

    /** Go-format v2 Stripe webhook transport JSON. */
    private static final String VALID_STRIPE_V2_JSON = """
            {
              "id": "wh_001",
              "psp": "stripe",
              "event_type": "payment_intent.succeeded",
              "payment_id": "pi_abc",
              "order_id": "ord_123",
              "amount_cents": 5000,
              "currency": "usd",
              "status": "succeeded",
              "received_at": "2024-06-01T12:00:00Z",
              "schema_version": 2,
              "raw_psp_payload": {
                "id": "evt_stripe_1",
                "type": "payment_intent.succeeded",
                "data": { "object": { "id": "pi_abc" } }
              }
            }
            """;

    /** v1 message (no raw_psp_payload) — bridge should skip. */
    private static final String V1_JSON = """
            {
              "id": "wh_old",
              "psp": "stripe",
              "event_type": "charge.succeeded",
              "payment_id": "pi_old",
              "order_id": "ord_old",
              "amount_cents": 1000,
              "currency": "usd",
              "status": "succeeded",
              "received_at": "2024-01-01T00:00:00Z",
              "schema_version": 1
            }
            """;

    // ---------------------------------------------------------------
    // Happy path — deserialization + bridge delegation
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("successful deserialization and delegation")
    class HappyPath {

        @Test
        @DisplayName("valid v2 Stripe record is deserialized and forwarded to bridge")
        void validStripeRecord() throws Exception {
            when(bridge.forward(any(WebhookTransportEvent.class))).thenReturn(Result.PROCESSED);

            consumer.onWebhookEvent(record(VALID_STRIPE_V2_JSON));

            ArgumentCaptor<WebhookTransportEvent> captor =
                ArgumentCaptor.forClass(WebhookTransportEvent.class);
            verify(bridge).forward(captor.capture());

            WebhookTransportEvent forwarded = captor.getValue();
            org.assertj.core.api.Assertions.assertThat(forwarded.id()).isEqualTo("wh_001");
            org.assertj.core.api.Assertions.assertThat(forwarded.psp()).isEqualTo("stripe");
            org.assertj.core.api.Assertions.assertThat(forwarded.eventType()).isEqualTo("payment_intent.succeeded");
            org.assertj.core.api.Assertions.assertThat(forwarded.schemaVersion()).isEqualTo(2);
            org.assertj.core.api.Assertions.assertThat(forwarded.rawPspPayload()).isNotNull();
            org.assertj.core.api.Assertions.assertThat(forwarded.rawPspPayload().toString())
                .contains("evt_stripe_1");
        }

        @Test
        @DisplayName("unknown JSON fields are ignored (forward compatibility)")
        void unknownFieldsIgnored() throws Exception {
            String jsonWithExtra = """
                {
                  "id": "wh_extra",
                  "psp": "stripe",
                  "event_type": "charge.refunded",
                  "payment_id": "pi_x",
                  "order_id": "ord_x",
                  "amount_cents": 100,
                  "currency": "usd",
                  "status": "refunded",
                  "received_at": "2024-06-01T00:00:00Z",
                  "schema_version": 2,
                  "raw_psp_payload": {"id":"evt_x"},
                  "future_field": "should be silently ignored"
                }
                """;
            when(bridge.forward(any(WebhookTransportEvent.class))).thenReturn(Result.PROCESSED);

            assertThatCode(() -> consumer.onWebhookEvent(record(jsonWithExtra)))
                .doesNotThrowAnyException();
            verify(bridge).forward(any(WebhookTransportEvent.class));
        }
    }

    // ---------------------------------------------------------------
    // Skipped bridge outcomes must not throw
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("skipped bridge results do not throw")
    class SkippedResults {

        @Test
        @DisplayName("SKIPPED_SCHEMA_VERSION_TOO_OLD completes normally")
        void skippedSchemaVersion() throws Exception {
            when(bridge.forward(any(WebhookTransportEvent.class)))
                .thenReturn(Result.SKIPPED_SCHEMA_VERSION_TOO_OLD);

            assertThatCode(() -> consumer.onWebhookEvent(record(V1_JSON)))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("SKIPPED_UNSUPPORTED_PSP completes normally")
        void skippedUnsupportedPsp() throws Exception {
            when(bridge.forward(any(WebhookTransportEvent.class)))
                .thenReturn(Result.SKIPPED_UNSUPPORTED_PSP);

            assertThatCode(() -> consumer.onWebhookEvent(record(VALID_STRIPE_V2_JSON)))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("SKIPPED_MISSING_RAW_PAYLOAD completes normally")
        void skippedMissingPayload() throws Exception {
            when(bridge.forward(any(WebhookTransportEvent.class)))
                .thenReturn(Result.SKIPPED_MISSING_RAW_PAYLOAD);

            assertThatCode(() -> consumer.onWebhookEvent(record(VALID_STRIPE_V2_JSON)))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("SKIPPED_VALIDATION_FAILED completes normally")
        void skippedValidation() throws Exception {
            when(bridge.forward(any(WebhookTransportEvent.class)))
                .thenReturn(Result.SKIPPED_VALIDATION_FAILED);

            assertThatCode(() -> consumer.onWebhookEvent(record(VALID_STRIPE_V2_JSON)))
                .doesNotThrowAnyException();
        }
    }

    // ---------------------------------------------------------------
    // Error propagation — errors must bubble up for retry/DLT
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("error propagation for retry/DLT")
    class ErrorPropagation {

        @Test
        @DisplayName("malformed JSON propagates JsonProcessingException")
        void malformedJsonPropagates() {
            ConsumerRecord<String, String> bad = record("not-valid-json{{{");

            assertThatThrownBy(() -> consumer.onWebhookEvent(bad))
                .isInstanceOf(JsonProcessingException.class);
            verifyNoInteractions(bridge);
        }

        @Test
        @DisplayName("empty string propagates JsonProcessingException")
        void emptyPayloadPropagates() {
            ConsumerRecord<String, String> empty = record("");

            assertThatThrownBy(() -> consumer.onWebhookEvent(empty))
                .isInstanceOf(Exception.class);
            verifyNoInteractions(bridge);
        }

        @Test
        @DisplayName("bridge RuntimeException propagates for CommonErrorHandler")
        void bridgeExceptionPropagates() throws Exception {
            when(bridge.forward(any(WebhookTransportEvent.class)))
                .thenThrow(new RuntimeException("handler blew up"));

            assertThatThrownBy(() -> consumer.onWebhookEvent(record(VALID_STRIPE_V2_JSON)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("handler blew up");
        }

        @Test
        @DisplayName("null record value propagates exception")
        void nullValuePropagates() {
            ConsumerRecord<String, String> nullVal =
                new ConsumerRecord<>(TOPIC, 0, 0L, "key", null);

            assertThatThrownBy(() -> consumer.onWebhookEvent(nullVal))
                .isInstanceOf(Exception.class);
            verifyNoInteractions(bridge);
        }
    }
}
