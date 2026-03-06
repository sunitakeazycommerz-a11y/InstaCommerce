package com.instacommerce.checkout.workflow.activity;

import com.instacommerce.checkout.dto.PaymentAuthResult;
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
    public PaymentAuthResult authorizePayment(long amountCents, String paymentMethodId, String idempotencyKey) {
        String resolvedKey = resolveIdempotencyKey(idempotencyKey);
        log.info("Authorizing payment amount={} paymentMethod={} idempotencyKey={}", amountCents, paymentMethodId, resolvedKey);
        return restTemplate.postForObject("/payments/authorize",
            Map.of("amountCents", amountCents, "paymentMethodId", paymentMethodId, "idempotencyKey", resolvedKey),
            PaymentAuthResult.class);
    }

    @Override
    public void capturePayment(String paymentId, String idempotencyKey) {
        String resolvedKey = resolveIdempotencyKey(idempotencyKey);
        log.info("Capturing payment={} idempotencyKey={}", paymentId, resolvedKey);
        restTemplate.postForObject("/payments/{paymentId}/capture",
            Map.of("idempotencyKey", resolvedKey), Void.class, paymentId);
    }

    @Override
    public void voidPayment(String paymentId, String idempotencyKey) {
        String resolvedKey = resolveIdempotencyKey(idempotencyKey);
        log.info("Voiding payment={} idempotencyKey={}", paymentId, resolvedKey);
        restTemplate.postForObject("/payments/{paymentId}/void",
            Map.of("idempotencyKey", resolvedKey), Void.class, paymentId);
    }

    @Override
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

    private String resolveIdempotencyKey(String providedKey) {
        String activityId = Activity.getExecutionContext().getInfo().getActivityId();
        if (providedKey == null || providedKey.isBlank()) {
            return activityId;
        }
        return providedKey + "-" + activityId;
    }
}
