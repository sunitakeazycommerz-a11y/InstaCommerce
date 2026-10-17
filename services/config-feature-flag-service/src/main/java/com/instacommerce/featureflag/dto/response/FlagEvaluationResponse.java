package com.instacommerce.featureflag.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record FlagEvaluationResponse(
    String key,
    Object value,
    String source
) {
    public static final String SOURCE_DEFAULT = "DEFAULT";
    public static final String SOURCE_OVERRIDE = "OVERRIDE";
    public static final String SOURCE_PERCENTAGE = "PERCENTAGE";
    public static final String SOURCE_USER_LIST = "USER_LIST";

    @JsonProperty("enabled")
    public boolean isEnabled() {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return value != null;
    }
}
