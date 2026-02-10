package com.instacommerce.featureflag.dto.response;

import java.util.UUID;

public record ExperimentEvaluationResponse(
    String key,
    UUID experimentId,
    String variant,
    UUID variantId,
    Object payload,
    String source,
    Long switchbackWindow,
    UUID exposureId
) {
    public static final String SOURCE_ASSIGNED = "ASSIGNED";
    public static final String SOURCE_INACTIVE = "INACTIVE";
    public static final String SOURCE_NOT_FOUND = "NOT_FOUND";
    public static final String SOURCE_NO_VARIANTS = "NO_VARIANTS";
}
