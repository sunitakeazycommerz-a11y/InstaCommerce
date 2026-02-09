package com.instacommerce.featureflag.dto.request;

import com.instacommerce.featureflag.domain.model.FlagType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record UpdateFlagRequest(
    @Size(max = 255) String name,
    String description,
    FlagType flagType,
    Boolean enabled,
    String defaultValue,
    @Min(0) @Max(100) Integer rolloutPercentage,
    String targetUsers,
    String metadata
) {
}
