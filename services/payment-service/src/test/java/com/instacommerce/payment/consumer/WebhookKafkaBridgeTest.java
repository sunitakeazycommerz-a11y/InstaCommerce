package com.instacommerce.payment.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.instacommerce.payment.consumer.WebhookKafkaBridge.Result;
import com.instacommerce.payment.webhook.WebhookEventHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WebhookKafkaBridgeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private WebhookEventHandler webhookEventHandler;

    private WebhookKafkaBridge bridge;

    @BeforeEach
    void setUp() {
        bridge = new WebhookKafkaBridge(webhookEventHandler);
    }

    private JsonNode rawPayloadNode(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    private WebhookTransportEvent stripeEvent(int schemaVersion, JsonNode rawPayload) {
        return new WebhookTransportEvent(
            "evt_123", "stripe", "payment_intent.succeeded",
            "pi_abc", "order_456", 5000, "usd", "succeeded",
            "2024-06-01T12:00:00Z", schemaVersion, rawPayload);
    }

    @Nested
    @DisplayName("validation failures")
    class ValidationFailures {

        @Test
        @DisplayName("missing id → SKIPPED_VALIDATION_FAILED")
        void missingId() {
            var event = new WebhookTransportEvent(
                null, "stripe", "payment_intent.succeeded",
                "pi_abc", "order_456", 5000, "usd", "succeeded",
                "2024-06-01T12:00:00Z", 2, null);

            assertThat(bridge.forward(event)).isEqualTo(Result.SKIPPED_VALIDATION_FAILED);
            verifyNoInteractions(webhookEventHandler);
        }

        @Test
        @DisplayName("blank psp → SKIPPED_VALIDATION_FAILED")
        void blankPsp() {
            var event = new WebhookTransportEvent(
                "evt_1", "", "payment_intent.succeeded",
                "pi_abc", "order_456", 5000, "usd", "succeeded",
                "2024-06-01T12:00:00Z", 2, null);

            assertThat(bridge.forward(event)).isEqualTo(Result.SKIPPED_VALIDATION_FAILED);
            verifyNoInteractions(webhookEventHandler);
        }

        @Test
        @DisplayName("missing event_type → SKIPPED_VALIDATION_FAILED")
        void missingEventType() {
            var event = new WebhookTransportEvent(
                "evt_1", "stripe", null,
                "pi_abc", "order_456", 5000, "usd", "succeeded",
                "2024-06-01T12:00:00Z", 2, null);

            assertThat(bridge.forward(event)).isEqualTo(Result.SKIPPED_VALIDATION_FAILED);
            verifyNoInteractions(webhookEventHandler);
        }
    }

    @Nested
    @DisplayName("schema version checks")
    class SchemaVersionChecks {

        @Test
        @DisplayName("v0 (legacy) → SKIPPED_SCHEMA_VERSION_TOO_OLD")
        void schemaVersionZero() {
            var event = stripeEvent(0, null);
            assertThat(bridge.forward(event)).isEqualTo(Result.SKIPPED_SCHEMA_VERSION_TOO_OLD);
            verifyNoInteractions(webhookEventHandler);
        }

        @Test
        @DisplayName("v1 → SKIPPED_SCHEMA_VERSION_TOO_OLD")
        void schemaVersionOne() {
            var event = stripeEvent(1, null);
            assertThat(bridge.forward(event)).isEqualTo(Result.SKIPPED_SCHEMA_VERSION_TOO_OLD);
            verifyNoInteractions(webhookEventHandler);
        }
    }

    @Nested
    @DisplayName("raw payload checks")
    class RawPayloadChecks {

        @Test
        @DisplayName("null raw_psp_payload → SKIPPED_MISSING_RAW_PAYLOAD")
        void nullRawPayload() {
            var event = stripeEvent(2, null);
            assertThat(bridge.forward(event)).isEqualTo(Result.SKIPPED_MISSING_RAW_PAYLOAD);
            verifyNoInteractions(webhookEventHandler);
        }

        @Test
        @DisplayName("JSON null raw_psp_payload → SKIPPED_MISSING_RAW_PAYLOAD")
        void jsonNullRawPayload() {
            var event = stripeEvent(2, NullNode.getInstance());
            assertThat(bridge.forward(event)).isEqualTo(Result.SKIPPED_MISSING_RAW_PAYLOAD);
            verifyNoInteractions(webhookEventHandler);
        }
    }

    @Nested
    @DisplayName("PSP routing")
    class PspRouting {

        @Test
        @DisplayName("razorpay → SKIPPED_UNSUPPORTED_PSP")
        void razorpaySkipped() throws Exception {
            var event = new WebhookTransportEvent(
                "evt_rz", "razorpay", "payment.captured",
                "pay_xyz", "order_789", 10000, "inr", "captured",
                "2024-06-01T12:00:00Z", 2, rawPayloadNode("{\"entity\":\"event\"}"));

            assertThat(bridge.forward(event)).isEqualTo(Result.SKIPPED_UNSUPPORTED_PSP);
            verifyNoInteractions(webhookEventHandler);
        }

        @Test
        @DisplayName("phonepe → SKIPPED_UNSUPPORTED_PSP")
        void phonepeSkipped() throws Exception {
            var event = new WebhookTransportEvent(
                "evt_pp", "phonepe", "payment.success",
                "txn_abc", "order_111", 7500, "inr", "success",
                "2024-06-01T12:00:00Z", 2, rawPayloadNode("{\"success\":true}"));

            assertThat(bridge.forward(event)).isEqualTo(Result.SKIPPED_UNSUPPORTED_PSP);
            verifyNoInteractions(webhookEventHandler);
        }
    }

    @Nested
    @DisplayName("successful forwarding")
    class SuccessfulForwarding {

        @Test
        @DisplayName("stripe v2 event forwards raw payload to handler")
        void stripeV2Forwarded() throws Exception {
            String rawStripeJson = "{\"id\":\"evt_123\",\"type\":\"payment_intent.succeeded\","
                + "\"data\":{\"object\":{\"id\":\"pi_abc\",\"payment_intent\":\"pi_abc\"}}}";
            var event = stripeEvent(2, rawPayloadNode(rawStripeJson));

            Result result = bridge.forward(event);

            assertThat(result).isEqualTo(Result.PROCESSED);
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(webhookEventHandler).handle(captor.capture());
            assertThat(captor.getValue()).contains("\"id\":\"evt_123\"");
            assertThat(captor.getValue()).contains("\"type\":\"payment_intent.succeeded\"");
        }

        @Test
        @DisplayName("stripe v3+ event is also forwarded")
        void stripeV3Forwarded() throws Exception {
            String rawStripeJson = "{\"id\":\"evt_456\",\"type\":\"charge.refunded\","
                + "\"data\":{\"object\":{\"id\":\"ch_xyz\",\"payment_intent\":\"pi_def\"}}}";
            var event = stripeEvent(3, rawPayloadNode(rawStripeJson));

            assertThat(bridge.forward(event)).isEqualTo(Result.PROCESSED);
            verify(webhookEventHandler).handle(org.mockito.ArgumentMatchers.contains("evt_456"));
        }
    }

    @Test
    @DisplayName("full round-trip deserialization from JSON")
    void roundTripDeserialization() throws Exception {
        String kafkaJson = """
            {
              "id": "wh_99",
              "psp": "stripe",
              "event_type": "payment_intent.succeeded",
              "payment_id": "pi_round",
              "order_id": "ord_trip",
              "amount_cents": 2500,
              "currency": "usd",
              "status": "succeeded",
              "received_at": "2024-06-01T00:00:00Z",
              "schema_version": 2,
              "raw_psp_payload": {"id":"evt_rt","type":"payment_intent.succeeded","data":{"object":{"id":"pi_round","payment_intent":"pi_round"}}}
            }
            """;
        WebhookTransportEvent event = MAPPER.readValue(kafkaJson, WebhookTransportEvent.class);

        assertThat(event.id()).isEqualTo("wh_99");
        assertThat(event.psp()).isEqualTo("stripe");
        assertThat(event.schemaVersion()).isEqualTo(2);
        assertThat(event.rawPspPayload()).isNotNull();
        assertThat(event.rawPspPayload().toString()).contains("evt_rt");

        assertThat(bridge.forward(event)).isEqualTo(Result.PROCESSED);
        verify(webhookEventHandler).handle(org.mockito.ArgumentMatchers.contains("evt_rt"));
    }
}
