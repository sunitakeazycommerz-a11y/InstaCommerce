package com.instacommerce.fraud.dto.response;

import java.util.List;
import java.util.UUID;

public record FraudCheckResponse(
        int score,
        String riskLevel,
        String action,
        List<String> rulesTriggered,
        UUID signalId
) {
}
