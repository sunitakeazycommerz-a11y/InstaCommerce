package com.instacommerce.featureflag.dto.request;

import com.instacommerce.featureflag.domain.model.ExperimentStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public record CreateExperimentRequest(
    @NotBlank @Size(max = 120) String key,
    @Size(max = 255) String name,
    String description,
    ExperimentStatus status,
    @Size(max = 50) String assignmentUnit,
    Instant startAt,
    Instant endAt,
    Boolean switchbackEnabled,
    @Min(1) Integer switchbackIntervalMinutes,
    Instant switchbackStartAt,
    String metadata,
    @NotEmpty List<@Valid ExperimentVariantRequest> variants
) {
}
