package com.instacommerce.payment.consumer;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope(
    @JsonAlias({"id", "eventId"})
    String id,
    @JsonAlias("aggregateId")
    String aggregateId,
    @JsonAlias("eventType")
    String eventType,
    JsonNode payload
) {
}
