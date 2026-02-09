package com.instacommerce.featureflag.dto.request;

import com.instacommerce.featureflag.domain.model.FlagType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateFlagRequest(
    @NotBlank @Size(max = 100) String key,
    @Size(max = 255) String name,
    String description,
    FlagType flagType,
    Boolean enabled,
    String defaultValue,
    @Min(0) @Max(100) Integer rolloutPercentage,
    String targetUsers,
    String metadata,
    String createdBy
) {
}
