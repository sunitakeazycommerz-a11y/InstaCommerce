package com.instacommerce.checkout.workflow.activity;

import com.instacommerce.checkout.dto.PaymentAuthResult;
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
        log.info("Authorizing payment amount={} paymentMethod={} idempotencyKey={}", amountCents, paymentMethodId, idempotencyKey);
        return restTemplate.postForObject("/api/payments/authorize",
            Map.of("amountCents", amountCents, "paymentMethodId", paymentMethodId, "idempotencyKey", idempotencyKey),
            PaymentAuthResult.class);
    }

    @Override
    public void capturePayment(String paymentId) {
        log.info("Capturing payment={}", paymentId);
        restTemplate.postForObject("/api/payments/{paymentId}/capture", null, Void.class, paymentId);
    }

    @Override
    public void voidPayment(String paymentId) {
        log.info("Voiding payment={}", paymentId);
        restTemplate.postForObject("/api/payments/{paymentId}/void", null, Void.class, paymentId);
    }
}
