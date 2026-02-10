package com.instacommerce.featureflag.dto.response;

import java.util.UUID;

public record ExperimentVariantResponse(
    UUID id,
    String name,
    int weight,
    Object payload,
    boolean control
) {
}
