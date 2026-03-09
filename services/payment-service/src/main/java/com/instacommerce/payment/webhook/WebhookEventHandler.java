package com.instacommerce.payment.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.repository.ProcessedWebhookEventRepository;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Parses Stripe webhook payloads, performs fast-path dedup, and delegates
 * transactional event processing to {@link WebhookEventProcessor}.
 * <p>
 * The processing call goes through a separate Spring bean so that the
 * {@code @Transactional} proxy is honoured. A previous version called
 * {@code this.processEvent()} (self-invocation), which silently bypassed the
 * proxy and broke dedup-marker rollback on transient deferrals.
 */
@Component
public class WebhookEventHandler {
    private static final Logger log = LoggerFactory.getLogger(WebhookEventHandler.class);
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MS = 100;

    private final ObjectMapper objectMapper;
    private final ProcessedWebhookEventRepository processedWebhookEventRepository;
    private final WebhookEventProcessor processor;

    public WebhookEventHandler(ObjectMapper objectMapper,
                               ProcessedWebhookEventRepository processedWebhookEventRepository,
                               WebhookEventProcessor processor) {
        this.objectMapper = objectMapper;
        this.processedWebhookEventRepository = processedWebhookEventRepository;
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

        String eventId = textValue(root, "id");
        String type = textValue(root, "type");
        if (type == null || type.isBlank()) {
            return;
        }

        // Fast-path deduplication before entering a transaction
        if (eventId != null && !eventId.isBlank()
            && processedWebhookEventRepository.existsById(eventId)) {
            log.debug("Webhook event {} already processed, skipping", eventId);
            return;
        }

        JsonNode objectNode = root.path("data").path("object");
        String pspReference = extractPspReference(objectNode);
        if (pspReference == null || pspReference.isBlank()) {
            log.debug("Webhook missing PSP reference for event {}", type);
            return;
        }

        // Retry with backoff on optimistic lock conflicts
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                processor.processEvent(eventId, type, pspReference, objectNode);
                return;
            } catch (ObjectOptimisticLockingFailureException ex) {
                if (attempt == MAX_RETRY_ATTEMPTS) {
                    log.error("Webhook event {} failed after {} retries due to optimistic lock conflict",
                        eventId, MAX_RETRY_ATTEMPTS, ex);
                    throw ex;
                }
                log.warn("Optimistic lock conflict on webhook event {}, retrying (attempt {}/{})",
                    eventId, attempt, MAX_RETRY_ATTEMPTS);
                try {
                    Thread.sleep(RETRY_BACKOFF_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during webhook retry", ie);
                }
            }
        }
    }

    /**
     * Thrown when a refund webhook arrives for a payment in a transient non-refundable state.
     * Rolling back the transaction (including the dedup record) allows Stripe to retry.
     */
    static class TransientWebhookStateException extends RuntimeException {
        TransientWebhookStateException(UUID paymentId, PaymentStatus status) {
            super("Payment %s in transient state %s; deferring for retry".formatted(paymentId, status));
        }
    }

    private String extractPspReference(JsonNode objectNode) {
        String paymentIntent = textValue(objectNode, "payment_intent");
        if (paymentIntent != null && !paymentIntent.isBlank()) {
            return paymentIntent;
        }
        return textValue(objectNode, "id");
    }

    private String textValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.asText();
    }
}
