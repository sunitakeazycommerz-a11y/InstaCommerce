package com.instacommerce.mobilebff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record HomeResponse(
    String status,
    List<Map<String, Object>> categories,
    List<String> trending,
    Map<String, Object> flags,
    Map<String, Object> metadata
) {
    public static HomeResponse of(
        List<Map<String, Object>> categories,
        List<String> trending,
        Map<String, Object> flags,
        Map<String, Object> metadata
    ) {
        return new HomeResponse("ok", categories, trending, flags, metadata);
    }

    public static HomeResponse fallback(String reason) {
        String sanitizedReason = (reason != null && reason.matches("[a-zA-Z0-9_.-]{1,64}"))
            ? reason
            : "upstream_unavailable";
        return new HomeResponse(
            "ok",
            List.of(),
            List.of(),
            Map.of(),
            Map.of("degraded", true, "reason", sanitizedReason)
        );
    }
}
