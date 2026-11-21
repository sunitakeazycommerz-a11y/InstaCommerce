package com.instacommerce.payment.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.repository.LedgerEntryRepository;
import java.util.UUID;

final class WebhookPayloadUtils {
    private WebhookPayloadUtils() {
    }

    static long longValue(JsonNode node, String... fields) {
        if (node == null) {
            return 0;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && value.isNumber()) {
                return value.asLong();
            }
        }
        return 0;
    }

    static String textValue(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    static String referenceId(String eventId, Payment payment) {
        return (eventId == null || eventId.isBlank()) ? payment.getId().toString() : eventId;
    }

    static boolean hasAuthorizationLedgerEntry(LedgerEntryRepository ledgerEntryRepository, UUID paymentId) {
        return ledgerEntryRepository.existsByPaymentIdAndReferenceTypeAndReferenceId(
            paymentId, "AUTHORIZATION", paymentId.toString());
    }

    static String extractFailureReason(JsonNode objectNode, PaymentStatus previousStatus) {
        if (objectNode != null) {
            JsonNode errorNode = objectNode.path("last_payment_error");
            String message = textValue(errorNode, "message");
            if (message != null && !message.isBlank()) {
                return message;
            }
            String code = textValue(errorNode, "code");
            if (code != null && !code.isBlank()) {
                return "PSP error: " + code;
            }
        }
        return "Payment failed during " + previousStatus.name();
    }
}
