package com.instacommerce.pricing.consumer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope(
    String id,
    String aggregateId,
    String eventType,
    JsonNode payload
) {
}
