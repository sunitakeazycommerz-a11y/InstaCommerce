package com.instacommerce.wallet.client;

import com.instacommerce.wallet.exception.ApiException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Client for the payment-service. Verifies payment transactions
 * before crediting wallet balances to prevent free-money exploits.
 */
@Component
public class PaymentClient {
    private static final Logger log = LoggerFactory.getLogger(PaymentClient.class);

    private final RestClient restClient;

    public PaymentClient(@Value("${payment-service.base-url:http://payment-service:8080}") String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(java.time.Duration.ofMillis(2000));
        requestFactory.setReadTimeout(java.time.Duration.ofMillis(3000));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * Verifies a payment reference with the payment-service.
     *
     * @param paymentReference the payment transaction ID to verify
     * @return the payment verification response
     * @throws ApiException if payment-service is unavailable or returns an error
     */
    @CircuitBreaker(name = "paymentService", fallbackMethod = "verifyPaymentFallback")
    @Retry(name = "paymentService")
    public PaymentResponse verifyPayment(String paymentReference) {
        PaymentResponse response = restClient.get()
                .uri("/api/v1/payments/{paymentReference}", paymentReference)
                .retrieve()
                .body(PaymentResponse.class);
        if (response == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_SERVICE_UNAVAILABLE",
                    "Payment service returned null for reference: " + paymentReference);
        }
        return response;
    }

    private PaymentResponse verifyPaymentFallback(String paymentReference, Exception e) {
        log.warn("Circuit breaker fallback for paymentService verifyPayment reference={}: {}",
                paymentReference, e.getMessage());
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_SERVICE_UNAVAILABLE",
                "Unable to verify payment. Please try again later.");
    }

    /**
     * Response model for the payment-service GET /api/v1/payments/{paymentReference} endpoint.
     */
    public record PaymentResponse(String paymentReference, String status, long amountCents, String currency) {

        /** Returns true if the payment has been completed successfully. */
        public boolean isCompleted() {
            return "COMPLETED".equalsIgnoreCase(status);
        }
    }
}
