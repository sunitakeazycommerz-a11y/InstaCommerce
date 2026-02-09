package com.instacommerce.fraud.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BlockRequest(
        @NotBlank String entityType,
        @NotBlank String entityValue,
        @NotNull String reason,
        Instant expiresAt
) {
}
