package com.instacommerce.fraud.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FraudReportRequest(
        UUID userId,
        UUID orderId,
        String deviceFingerprint,
        String ipAddress,
        String reason
) {
}
