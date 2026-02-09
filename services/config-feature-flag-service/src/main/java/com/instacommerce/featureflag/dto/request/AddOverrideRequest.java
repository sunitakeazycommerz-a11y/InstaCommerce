package com.instacommerce.featureflag.dto.request;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public record AddOverrideRequest(
    @NotNull UUID userId,
    @NotNull String value,
    String reason,
    Instant expiresAt
) {
}
