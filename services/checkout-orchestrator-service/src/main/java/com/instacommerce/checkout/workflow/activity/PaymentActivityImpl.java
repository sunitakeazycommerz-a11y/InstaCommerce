package com.instacommerce.checkout.workflow.activity;

import com.instacommerce.checkout.dto.PaymentAuthResult;
import com.instacommerce.checkout.exception.ServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.temporal.activity.Activity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Component
public class PaymentActivityImpl implements PaymentActivity {
    private static final Logger log = LoggerFactory.getLogger(PaymentActivityImpl.class);
    private final RestTemplate restTemplate;

    public PaymentActivityImpl(@Qualifier("paymentRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    @CircuitBreaker(name = "paymentService", fallbackMethod = "authorizePaymentFallback")
    public PaymentAuthResult authorizePayment(long amountCents, String paymentMethodId, String idempotencyKey) {
        String resolvedKey = resolveIdempotencyKey(idempotencyKey);
        log.info("Authorizing payment amount={} paymentMethod={} idempotencyKey={}", amountCents, paymentMethodId, resolvedKey);
        return restTemplate.postForObject("/payments/authorize",
            Map.of("amountCents", amountCents, "paymentMethodId", paymentMethodId, "idempotencyKey", resolvedKey),
            PaymentAuthResult.class);
    }

    @Override
    @CircuitBreaker(name = "paymentService", fallbackMethod = "capturePaymentFallback")
    public void capturePayment(String paymentId, String idempotencyKey) {
        String resolvedKey = resolveIdempotencyKey(idempotencyKey);
        log.info("Capturing payment={} idempotencyKey={}", paymentId, resolvedKey);
        restTemplate.postForObject("/payments/{paymentId}/capture",
            Map.of("idempotencyKey", resolvedKey), Void.class, paymentId);
    }

    @Override
    @CircuitBreaker(name = "paymentService", fallbackMethod = "voidPaymentFallback")
    public void voidPayment(String paymentId, String idempotencyKey) {
        String resolvedKey = resolveIdempotencyKey(idempotencyKey);
        log.info("Voiding payment={} idempotencyKey={}", paymentId, resolvedKey);
        restTemplate.postForObject("/payments/{paymentId}/void",
            Map.of("idempotencyKey", resolvedKey), Void.class, paymentId);
    }

    @Override
    @CircuitBreaker(name = "paymentService", fallbackMethod = "refundPaymentFallback")
    public void refundPayment(String paymentId, long amountCents, String idempotencyKey) {
        String resolvedKey = resolveIdempotencyKey(idempotencyKey);
        log.info("Refunding payment={} amountCents={} idempotencyKey={}", paymentId, amountCents, resolvedKey);
        restTemplate.postForObject("/payments/{paymentId}/refund",
            Map.of(
                "amountCents", amountCents,
                "reason", "CHECKOUT_COMPENSATION",
                "idempotencyKey", resolvedKey
            ), Void.class, paymentId);
    }

    private PaymentAuthResult authorizePaymentFallback(long amountCents, String paymentMethodId,
                                                        String idempotencyKey, Exception e) {
        log.warn("Circuit breaker fallback for paymentService authorizePayment amount={}: {}",
                amountCents, e.getMessage());
        throw new ServiceUnavailableException("paymentService",
                "Payment service unavailable for authorizePayment", e);
    }

    private void capturePaymentFallback(String paymentId, String idempotencyKey, Exception e) {
        log.warn("Circuit breaker fallback for paymentService capturePayment paymentId={}: {}",
                paymentId, e.getMessage());
        throw new ServiceUnavailableException("paymentService",
                "Payment service unavailable for capturePayment paymentId=" + paymentId, e);
    }

    private void voidPaymentFallback(String paymentId, String idempotencyKey, Exception e) {
        log.warn("Circuit breaker fallback for paymentService voidPayment paymentId={}: {}",
                paymentId, e.getMessage());
        throw new ServiceUnavailableException("paymentService",
                "Payment service unavailable for voidPayment paymentId=" + paymentId, e);
    }

    private void refundPaymentFallback(String paymentId, long amountCents, String idempotencyKey, Exception e) {
        log.warn("Circuit breaker fallback for paymentService refundPayment paymentId={}: {}",
                paymentId, e.getMessage());
        throw new ServiceUnavailableException("paymentService",
                "Payment service unavailable for refundPayment paymentId=" + paymentId, e);
    }

    private String resolveIdempotencyKey(String providedKey) {
        if (providedKey != null && !providedKey.isBlank()) {
            return providedKey;
        }
        // Fallback: use Temporal activityId which is stable across retries of the
        // same activity schedule, but callers should prefer workflow-stable keys.
        return Activity.getExecutionContext().getInfo().getActivityId();
    }
}
