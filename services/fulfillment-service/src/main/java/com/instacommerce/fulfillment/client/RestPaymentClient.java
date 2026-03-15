package com.instacommerce.fulfillment.client;

import com.instacommerce.fulfillment.config.FulfillmentProperties;
import com.instacommerce.fulfillment.exception.ServiceUnavailableException;
import com.instacommerce.fulfillment.security.InternalServiceAuthInterceptor;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class RestPaymentClient implements PaymentClient {
    private static final Logger logger = LoggerFactory.getLogger(RestPaymentClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public RestPaymentClient(FulfillmentProperties fulfillmentProperties,
                             @Value("${internal.service.name:${spring.application.name}}") String serviceName,
                             @Value("${internal.service.token}") String serviceToken) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restTemplate = new RestTemplate(requestFactory);
        this.restTemplate.getInterceptors().add(new InternalServiceAuthInterceptor(serviceName, serviceToken));
        this.baseUrl = fulfillmentProperties.getClients().getPayment().getBaseUrl();
    }

    @Override
    @CircuitBreaker(name = "paymentService", fallbackMethod = "refundFallback")
    @Retry(name = "paymentService")
    public void refund(UUID paymentId, long amountCents, String reason, String idempotencyKey) {
        RefundRequest request = new RefundRequest(amountCents, reason, idempotencyKey);
        restTemplate.postForObject(baseUrl + "/payments/" + paymentId + "/refund", request, Object.class);
    }

    private void refundFallback(UUID paymentId, long amountCents, String reason, String idempotencyKey, Exception e) {
        logger.warn("Circuit breaker fallback for paymentService refund paymentId={} amount={}: {}",
                paymentId, amountCents, e.getMessage());
        throw new ServiceUnavailableException("paymentService",
                "Payment service unavailable for refund paymentId=" + paymentId, e);
    }
}
