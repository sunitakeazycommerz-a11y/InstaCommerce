package com.instacommerce.payment.service;

import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.Refund;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class PaymentOutboxFactory {
    private final OutboxService outboxService;

    public PaymentOutboxFactory(OutboxService outboxService) {
        this.outboxService = outboxService;
    }

    public void publishAuthorized(Payment payment) {
        publishAuthorized(payment, null);
    }

    public void publishAuthorized(Payment payment, String resolvedBy) {
        Map<String, Object> payload = basePaymentPayload(payment, payment.getAmountCents());
        if (resolvedBy != null) {
            payload.put("resolvedBy", resolvedBy);
        }
        outboxService.publish("Payment", payment.getId().toString(), "PaymentAuthorized", payload);
    }

    public void publishCaptured(Payment payment, long capturedCents) {
        publishCaptured(payment, capturedCents, null);
    }

    public void publishCaptured(Payment payment, long capturedCents, String resolvedBy) {
        Map<String, Object> payload = basePaymentPayload(payment, capturedCents);
        if (resolvedBy != null) {
            payload.put("resolvedBy", resolvedBy);
        }
        outboxService.publish("Payment", payment.getId().toString(), "PaymentCaptured", payload);
    }

    public void publishVoided(Payment payment, Instant voidedAt) {
        Map<String, Object> payload = basePaymentPayload(payment, payment.getAmountCents());
        payload.put("voidedAt", formatTimestamp(voidedAt));
        outboxService.publish("Payment", payment.getId().toString(), "PaymentVoided", payload);
    }

    public void publishFailed(Payment payment, String reason, String resolvedBy) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", payment.getOrderId());
        payload.put("paymentId", payment.getId());
        payload.put("reason", reason);
        if (resolvedBy != null) {
            payload.put("resolvedBy", resolvedBy);
        }
        if (payment.getMetadata() != null && payment.getMetadata().get("userId") != null) {
            payload.put("userId", payment.getMetadata().get("userId").toString());
        }
        outboxService.publish("Payment", payment.getId().toString(), "PaymentFailed", payload);
    }

    public void publishReverted(Payment payment, String eventType, String reason, String resolvedBy) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", payment.getOrderId());
        payload.put("paymentId", payment.getId());
        payload.put("reason", reason);
        if (resolvedBy != null) {
            payload.put("resolvedBy", resolvedBy);
        }
        outboxService.publish("Payment", payment.getId().toString(), eventType, payload);
    }

    public void publishRefunded(Payment payment, UUID refundId, long amountCents, Instant refundedAt, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", payment.getOrderId());
        payload.put("paymentId", payment.getId());
        payload.put("refundId", refundId);
        payload.put("amountCents", amountCents);
        payload.put("currency", payment.getCurrency());
        payload.put("refundedAt", formatTimestamp(refundedAt));
        if (reason != null && !reason.isBlank()) {
            payload.put("reason", reason);
        }
        outboxService.publish("Payment", payment.getId().toString(), "PaymentRefunded", payload);
    }

    public void publishWebhookRefunded(Payment payment, String refundId, long amountCents,
                                       Instant refundedAt, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("refundId", refundId);
        payload.put("orderId", payment.getOrderId());
        payload.put("paymentId", payment.getId());
        payload.put("amountCents", amountCents);
        payload.put("currency", payment.getCurrency());
        payload.put("refundedAt", formatTimestamp(refundedAt));
        payload.put("reason", (reason != null && !reason.isBlank()) ? reason : "webhook");
        outboxService.publish("Payment", payment.getId().toString(), "PaymentRefunded", payload);
    }

    public void publishRefundFailed(Payment payment, Refund refund, String reason, String failureSource) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", payment.getOrderId());
        payload.put("paymentId", payment.getId());
        payload.put("refundId", refund.getId());
        payload.put("amountCents", refund.getAmountCents());
        payload.put("currency", payment.getCurrency());
        payload.put("reason", reason);
        payload.put("failureSource", failureSource);
        outboxService.publish("Payment", payment.getId().toString(), "PaymentRefundFailed", payload);
    }

    public void publishDisputed(Payment payment, long disputeAmountCents, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", payment.getOrderId());
        payload.put("paymentId", payment.getId());
        payload.put("disputeAmountCents", disputeAmountCents);
        payload.put("currency", payment.getCurrency());
        payload.put("reason", reason != null ? reason : "");
        outboxService.publish("Payment", payment.getId().toString(), "PaymentDisputed", payload);
    }

    public void publishDisputeUpdated(Payment payment, String disputeStatus, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", payment.getOrderId());
        payload.put("paymentId", payment.getId());
        payload.put("disputeStatus", disputeStatus != null ? disputeStatus : "");
        payload.put("currency", payment.getCurrency());
        payload.put("reason", reason != null ? reason : "");
        outboxService.publish("Payment", payment.getId().toString(), "PaymentDisputeUpdated", payload);
    }

    public void publishDisputeClosed(Payment payment, String eventType, long amountCents) {
        outboxService.publish("Payment", payment.getId().toString(), eventType,
            basePaymentPayload(payment, amountCents));
    }

    public static UUID syntheticRefundId(String eventId) {
        return syntheticRefundId(eventId, null, 0L);
    }

    public static UUID syntheticRefundId(String eventId, UUID paymentId, long refundDelta) {
        String key = (eventId != null && !eventId.isBlank())
            ? "refund:" + eventId
            : "refund:fallback:" + paymentId + ":" + refundDelta;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    public static String formatTimestamp(Instant timestamp) {
        return (timestamp != null ? timestamp : Instant.now()).toString();
    }

    private Map<String, Object> basePaymentPayload(Payment payment, long amountCents) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", payment.getOrderId());
        payload.put("paymentId", payment.getId());
        payload.put("amountCents", amountCents);
        payload.put("currency", payment.getCurrency());
        return payload;
    }
}
