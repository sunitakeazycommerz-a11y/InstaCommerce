package com.instacommerce.payment.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.domain.model.ProcessedWebhookEvent;
import com.instacommerce.payment.repository.PaymentRepository;
import com.instacommerce.payment.repository.ProcessedWebhookEventRepository;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WebhookEventHandler {
    private static final Logger log = LoggerFactory.getLogger(WebhookEventHandler.class);
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MS = 100;

    private final ObjectMapper objectMapper;
    private final PaymentRepository paymentRepository;
    private final ProcessedWebhookEventRepository processedWebhookEventRepository;

    public WebhookEventHandler(ObjectMapper objectMapper,
                               PaymentRepository paymentRepository,
                               ProcessedWebhookEventRepository processedWebhookEventRepository) {
        this.objectMapper = objectMapper;
        this.paymentRepository = paymentRepository;
        this.processedWebhookEventRepository = processedWebhookEventRepository;
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

        // Deduplication check
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
                processEvent(eventId, type, pspReference, objectNode);
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

    @Transactional
    public void processEvent(String eventId, String type, String pspReference, JsonNode objectNode) {
        // Record event as processed (dedup)
        if (eventId != null && !eventId.isBlank()) {
            try {
                ProcessedWebhookEvent processed = new ProcessedWebhookEvent();
                processed.setEventId(eventId);
                processedWebhookEventRepository.save(processed);
            } catch (DataIntegrityViolationException ex) {
                log.debug("Webhook event {} already processed (concurrent), skipping", eventId);
                return;
            }
        }

        paymentRepository.findByPspReference(pspReference)
            .ifPresent(payment -> applyEvent(payment, type, objectNode));
    }

    private void applyEvent(Payment payment, String type, JsonNode objectNode) {
        switch (type) {
            case "payment_intent.succeeded" -> handleCaptured(payment, objectNode);
            case "payment_intent.canceled" -> handleVoided(payment);
            case "payment_intent.payment_failed" -> handleFailed(payment);
            case "charge.refunded" -> handleRefunded(payment, objectNode);
            default -> log.debug("Ignoring webhook event {}", type);
        }
    }

    private void handleCaptured(Payment payment, JsonNode objectNode) {
        if (payment.getStatus() == PaymentStatus.CAPTURED) {
            return;
        }
        long amount = longValue(objectNode, "amount_received", "amount");
        if (amount > 0) {
            payment.setCapturedCents(Math.max(payment.getCapturedCents(), amount));
        }
        payment.setStatus(PaymentStatus.CAPTURED);
        paymentRepository.save(payment);
    }

    private void handleVoided(Payment payment) {
        if (payment.getStatus() == PaymentStatus.VOIDED) {
            return;
        }
        payment.setStatus(PaymentStatus.VOIDED);
        paymentRepository.save(payment);
    }

    private void handleFailed(Payment payment) {
        if (payment.getStatus() == PaymentStatus.FAILED) {
            return;
        }
        payment.setStatus(PaymentStatus.FAILED);
        paymentRepository.save(payment);
    }

    private void handleRefunded(Payment payment, JsonNode objectNode) {
        long refunded = longValue(objectNode, "amount_refunded");
        if (refunded > 0) {
            payment.setRefundedCents(Math.max(payment.getRefundedCents(), refunded));
            if (payment.getRefundedCents() >= payment.getCapturedCents()) {
                payment.setStatus(PaymentStatus.REFUNDED);
            } else {
                payment.setStatus(PaymentStatus.PARTIALLY_REFUNDED);
            }
            paymentRepository.save(payment);
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

    private long longValue(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && value.isNumber()) {
                return value.asLong();
            }
        }
        return 0;
    }
}
