package com.instacommerce.featureflag.dto.response;

public record FlagEvaluationResponse(
    String key,
    Object value,
    String source
) {
    public static final String SOURCE_DEFAULT = "DEFAULT";
    public static final String SOURCE_OVERRIDE = "OVERRIDE";
    public static final String SOURCE_PERCENTAGE = "PERCENTAGE";
}
