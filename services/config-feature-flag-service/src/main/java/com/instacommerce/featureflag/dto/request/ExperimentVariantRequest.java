package com.instacommerce.featureflag.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ExperimentVariantRequest(
    @NotBlank @Size(max = 100) String name,
    @Min(0) Integer weight,
    Boolean control,
    String payload
) {
}
