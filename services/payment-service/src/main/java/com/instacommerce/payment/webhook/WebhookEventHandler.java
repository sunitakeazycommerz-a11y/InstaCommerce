package com.instacommerce.payment.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.payment.domain.model.PaymentStatus;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Parses Stripe webhook payloads and delegates transactional event processing
 * to {@link WebhookEventProcessor}.
 */
@Component
public class WebhookEventHandler {
    private static final Logger log = LoggerFactory.getLogger(WebhookEventHandler.class);

    private final ObjectMapper objectMapper;
    private final WebhookEventProcessor processor;

    public WebhookEventHandler(ObjectMapper objectMapper,
                               WebhookEventProcessor processor) {
        this.objectMapper = objectMapper;
        this.processor = processor;
    }

    public void handle(String payload) {
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (IOException ex) {
            log.warn("Failed to parse webhook payload", ex);
            return;
        }

        String eventId = WebhookPayloadUtils.textValue(root, "id");
        String type = WebhookPayloadUtils.textValue(root, "type");
        if (type == null || type.isBlank()) {
            return;
        }

        JsonNode objectNode = root.path("data").path("object");
        String pspReference = extractPspReference(objectNode);
        if (pspReference == null || pspReference.isBlank()) {
            log.debug("Webhook missing PSP reference for event {}", type);
            return;
        }

        processor.processEvent(eventId, type, pspReference, objectNode, payload);
    }

    public static class TransientWebhookStateException extends RuntimeException {
        public TransientWebhookStateException(UUID paymentId, PaymentStatus status) {
            super("Payment %s in transient state %s; deferring for retry".formatted(paymentId, status));
        }
    }

    private String extractPspReference(JsonNode objectNode) {
        String paymentIntent = WebhookPayloadUtils.textValue(objectNode, "payment_intent");
        if (paymentIntent != null && !paymentIntent.isBlank()) {
            return paymentIntent;
        }
        return WebhookPayloadUtils.textValue(objectNode, "id");
    }
}
