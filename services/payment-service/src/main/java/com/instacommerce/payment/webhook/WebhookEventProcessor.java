package com.instacommerce.payment.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.domain.model.ProcessedWebhookEvent;
import com.instacommerce.payment.repository.PaymentRepository;
import com.instacommerce.payment.repository.ProcessedWebhookEventRepository;
import com.instacommerce.payment.service.LedgerService;
import com.instacommerce.payment.service.OutboxService;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional boundary for webhook event processing.
 * <p>
 * Extracted from {@link WebhookEventHandler} so that Spring's proxy-based AOP
 * enforces the {@code @Transactional} annotation. When the handler called its
 * own {@code processEvent()} method directly (self-invocation), the proxy was
 * bypassed and no real transaction was created — breaking dedup-marker rollback
 * on transient deferrals and the {@code MANDATORY} propagation required by
 * {@link LedgerService} and {@link OutboxService}.
 */
@Component
public class WebhookEventProcessor {
    private static final Logger log = LoggerFactory.getLogger(WebhookEventProcessor.class);

    static final Set<PaymentStatus> REFUNDABLE_STATES = Set.of(
        PaymentStatus.CAPTURED,
        PaymentStatus.PARTIALLY_REFUNDED,
        PaymentStatus.REFUNDED
    );

    static final Set<PaymentStatus> TERMINAL_NON_REFUNDABLE = Set.of(
        PaymentStatus.VOIDED,
        PaymentStatus.FAILED
    );

    private final PaymentRepository paymentRepository;
    private final ProcessedWebhookEventRepository processedWebhookEventRepository;
    private final LedgerService ledgerService;
    private final OutboxService outboxService;
    private final MeterRegistry meterRegistry;
    private final boolean refundOutboxEnabled;

    public WebhookEventProcessor(PaymentRepository paymentRepository,
                                 ProcessedWebhookEventRepository processedWebhookEventRepository,
                                 LedgerService ledgerService,
                                 OutboxService outboxService,
                                 MeterRegistry meterRegistry,
                                 @Value("${payment.webhook.refund-outbox-enabled:false}") boolean refundOutboxEnabled) {
        this.paymentRepository = paymentRepository;
        this.processedWebhookEventRepository = processedWebhookEventRepository;
        this.ledgerService = ledgerService;
        this.outboxService = outboxService;
        this.meterRegistry = meterRegistry;
        this.refundOutboxEnabled = refundOutboxEnabled;
    }

    @Transactional
    public void processEvent(String eventId, String type, String pspReference, JsonNode objectNode) {
        if (eventId != null && !eventId.isBlank()) {
            try {
                ProcessedWebhookEvent processed = new ProcessedWebhookEvent();
                processed.setEventId(eventId);
                processedWebhookEventRepository.saveAndFlush(processed);
            } catch (DataIntegrityViolationException ex) {
                log.debug("Webhook event {} already processed (concurrent), skipping", eventId);
                return;
            }
        }

        paymentRepository.findByPspReference(pspReference)
            .ifPresent(payment -> applyEvent(payment, type, objectNode, eventId));
    }

    private void applyEvent(Payment payment, String type, JsonNode objectNode, String eventId) {
        switch (type) {
            case "payment_intent.succeeded" -> handleCaptured(payment, objectNode, eventId);
            case "payment_intent.canceled" -> handleVoided(payment, eventId);
            case "payment_intent.payment_failed" -> handleFailed(payment);
            case "charge.refunded" -> handleRefunded(payment, objectNode, eventId);
            default -> log.debug("Ignoring webhook event {}", type);
        }
    }

    private void handleCaptured(Payment payment, JsonNode objectNode, String eventId) {
        if (payment.getStatus() == PaymentStatus.CAPTURED) {
            return;
        }
        long amount = longValue(objectNode, "amount_received", "amount");
        long capturedAmount = amount > 0 ? amount : payment.getAmountCents();
        long previousCaptured = payment.getCapturedCents();
        long delta = Math.max(0L, capturedAmount - previousCaptured);
        payment.setCapturedCents(Math.max(previousCaptured, capturedAmount));
        payment.setStatus(PaymentStatus.CAPTURED);
        Payment saved = paymentRepository.save(payment);
        if (delta > 0) {
            ledgerService.recordDoubleEntry(saved.getId(), delta,
                "authorization_hold", "merchant_payable", "CAPTURE",
                referenceId(eventId, saved), "Capture (webhook)");
        }
    }

    private void handleVoided(Payment payment, String eventId) {
        if (payment.getStatus() == PaymentStatus.VOIDED) {
            return;
        }
        payment.setStatus(PaymentStatus.VOIDED);
        Payment saved = paymentRepository.save(payment);
        ledgerService.recordDoubleEntry(saved.getId(), saved.getAmountCents(),
            "authorization_hold", "customer_receivable", "VOID",
            referenceId(eventId, saved), "Authorization void (webhook)");
    }

    private void handleFailed(Payment payment) {
        if (payment.getStatus() == PaymentStatus.FAILED) {
            return;
        }
        payment.setStatus(PaymentStatus.FAILED);
        paymentRepository.save(payment);
    }

    private void handleRefunded(Payment payment, JsonNode objectNode, String eventId) {
        if (TERMINAL_NON_REFUNDABLE.contains(payment.getStatus())) {
            log.warn("Webhook refund rejected: payment {} in terminal non-refundable state {}",
                payment.getId(), payment.getStatus());
            meterRegistry.counter("payment.webhook.refund", "outcome", "terminal_rejected").increment();
            return;
        }

        if (!REFUNDABLE_STATES.contains(payment.getStatus())) {
            meterRegistry.counter("payment.webhook.refund", "outcome", "transient_deferred").increment();
            throw new WebhookEventHandler.TransientWebhookStateException(payment.getId(), payment.getStatus());
        }

        long refunded = longValue(objectNode, "amount_refunded");
        if (refunded <= 0) {
            return;
        }
        long previousRefunded = payment.getRefundedCents();
        long updatedRefunded = Math.max(previousRefunded, refunded);
        long delta = updatedRefunded - previousRefunded;
        if (delta == 0) {
            return;
        }

        payment.setRefundedCents(updatedRefunded);
        if (updatedRefunded >= payment.getCapturedCents()) {
            payment.setStatus(PaymentStatus.REFUNDED);
        } else {
            payment.setStatus(PaymentStatus.PARTIALLY_REFUNDED);
        }
        Payment saved = paymentRepository.save(payment);

        ledgerService.recordDoubleEntry(saved.getId(), delta,
            "merchant_payable", "customer_receivable", "REFUND",
            referenceId(eventId, saved), "Refund (webhook)");

        meterRegistry.counter("payment.webhook.refund", "outcome", "processed").increment();

        if (refundOutboxEnabled) {
            publishRefundOutbox(saved, delta, eventId);
            meterRegistry.counter("payment.webhook.refund", "outcome", "outbox_published").increment();
        }
    }

    private void publishRefundOutbox(Payment payment, long refundDelta, String eventId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("refundId", syntheticRefundId(eventId).toString());
        payload.put("orderId", payment.getOrderId());
        payload.put("paymentId", payment.getId());
        payload.put("amountCents", refundDelta);
        payload.put("currency", payment.getCurrency());
        payload.put("refundedAt", Instant.now());
        payload.put("reason", "webhook");
        outboxService.publish("Payment", payment.getId().toString(), "PaymentRefunded", payload);
    }

    static UUID syntheticRefundId(String eventId) {
        if (eventId != null && !eventId.isBlank()) {
            return UUID.nameUUIDFromBytes(("refund:" + eventId).getBytes(StandardCharsets.UTF_8));
        }
        return UUID.randomUUID();
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

    private String referenceId(String eventId, Payment payment) {
        return (eventId == null || eventId.isBlank()) ? payment.getId().toString() : eventId;
    }
}
