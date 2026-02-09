package com.instacommerce.featureflag.dto.response;

import com.instacommerce.featureflag.domain.model.FlagType;
import java.time.Instant;
import java.util.UUID;

public record FlagResponse(
    UUID id,
    String key,
    String name,
    String description,
    FlagType flagType,
    boolean enabled,
    String defaultValue,
    int rolloutPercentage,
    String targetUsers,
    String metadata,
    String createdBy,
    Instant createdAt,
    Instant updatedAt,
    long version
) {
}
