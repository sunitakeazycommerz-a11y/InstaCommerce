package com.instacommerce.fulfillment.consumer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderEvent(
    String eventType,
    JsonNode payload
) {
}
