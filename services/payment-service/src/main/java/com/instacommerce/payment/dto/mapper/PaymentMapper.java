package com.instacommerce.payment.dto.mapper;

import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.dto.response.PaymentResponse;
import java.util.Map;
import java.util.UUID;

public final class PaymentMapper {
    private PaymentMapper() {
    }

    public static PaymentResponse toResponse(Payment payment) {
        Map<String, Object> metadata = payment.getMetadata();
        return new PaymentResponse(
            payment.getId(),
            payment.getStatus().name(),
            payment.getPspReference(),
            payment.getAmountCents(),
            payment.getCurrency(),
            extractUuid(metadata, "userId"),
            extractString(metadata, "purpose")
        );
    }

    private static UUID extractUuid(Map<String, Object> metadata, String key) {
        String raw = extractString(metadata, key);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String extractString(Map<String, Object> metadata, String key) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object raw = metadata.get(key);
        if (raw == null) {
            return null;
        }
        String text = raw.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
