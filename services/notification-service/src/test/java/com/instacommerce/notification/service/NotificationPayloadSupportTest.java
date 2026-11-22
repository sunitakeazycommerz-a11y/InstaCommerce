package com.instacommerce.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.instacommerce.contracts.events.EventEnvelope;
import com.instacommerce.notification.config.NotificationProperties;
import java.time.Instant;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NotificationPayloadSupportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("resolveUserId and resolveOrderSnapshot return empty cleanly on malformed UUIDs")
    void malformedUuidsReturnEmptySafely() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("userId", "not-a-valid-uuid");
        payload.put("orderId", "also-not-a-uuid");

        OrderLookupClient orderLookupClient = mock(OrderLookupClient.class);

        assertThat(NotificationPayloadSupport.resolveUserId(payload)).isEmpty();
        assertThat(NotificationPayloadSupport.resolveOrderSnapshot(payload, orderLookupClient)).isEmpty();
        verifyNoInteractions(orderLookupClient);
    }

    @Test
    @DisplayName("resolveEventId hashes oversized event keys with SHA-256 hex digest")
    void resolveEventIdHashesLongIdentifiers() {
        String longId = "long-event-id-" + "x".repeat(80);
        EventEnvelope envelope = new EventEnvelope(
            longId,
            "CustomAlert",
            "Order",
            "agg-1",
            Instant.now(),
            "1",
            "order-service",
            null,
            objectMapper.createObjectNode());
        ConsumerRecord<String, String> record = new ConsumerRecord<>("orders.events", 0, 1L, "k", "{}");

        String resolved = NotificationPayloadSupport.resolveEventId(record, envelope);

        assertThat(resolved)
            .hasSize(64)
            .matches("[0-9a-f]{64}")
            .doesNotStartWith("long-event-id");
    }

    @Test
    @DisplayName("buildVariables handles unknown event types cleanly via default branch")
    void buildVariablesHandlesUnknownEventType() {
        ObjectNode payload = objectMapper.createObjectNode().put("userName", "Asha");

        Map<String, Object> variables = NotificationPayloadSupport.buildVariables(
            "UnknownEventType", payload, null, null, new NotificationProperties());

        assertThat(variables).containsEntry("userName", "Asha");
    }
}
