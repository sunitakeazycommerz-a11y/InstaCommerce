package com.instacommerce.featureflag.dto.response;

import com.instacommerce.featureflag.domain.model.ExperimentStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ExperimentResponse(
    UUID id,
    String key,
    String name,
    String description,
    ExperimentStatus status,
    String assignmentUnit,
    Instant startAt,
    Instant endAt,
    boolean switchbackEnabled,
    Integer switchbackIntervalMinutes,
    Instant switchbackStartAt,
    String metadata,
    String createdBy,
    Instant createdAt,
    Instant updatedAt,
    long version,
    List<ExperimentVariantResponse> variants
) {
}
