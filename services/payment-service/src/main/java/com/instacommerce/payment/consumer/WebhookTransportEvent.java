package com.instacommerce.payment.consumer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Transport DTO for enriched webhook events produced by the Go
 * {@code payment-webhook-service} and published to Kafka.
 * <p>
 * Matches the {@code WebhookEvent} struct in
 * {@code services/payment-webhook-service/handler/webhook.go}.
 * V1 fields carry canonical, PSP-agnostic metadata; v2 adds
 * {@code schema_version} and {@code raw_psp_payload} so downstream
 * consumers can replay the exact PSP payload without information loss.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookTransportEvent(
    String id,
    String psp,
    @JsonProperty("event_type") String eventType,
    @JsonProperty("payment_id") String paymentId,
    @JsonProperty("order_id") String orderId,
    @JsonProperty("amount_cents") long amountCents,
    String currency,
    String status,
    @JsonProperty("received_at") String receivedAt,
    @JsonProperty("schema_version") int schemaVersion,
    @JsonProperty("raw_psp_payload") JsonNode rawPspPayload
) {
}
