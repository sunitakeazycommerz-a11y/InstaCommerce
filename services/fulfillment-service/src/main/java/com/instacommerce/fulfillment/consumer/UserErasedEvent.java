package com.instacommerce.fulfillment.consumer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UserErasedEvent(
    UUID userId,
    Instant erasedAt
) {
}
