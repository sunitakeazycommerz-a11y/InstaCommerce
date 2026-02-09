package com.instacommerce.search.dto;

import java.time.Instant;
import java.util.List;

public record ErrorResponse(
    String code,
    String message,
    String traceId,
    Instant timestamp,
    List<ErrorDetail> details
) {
}
