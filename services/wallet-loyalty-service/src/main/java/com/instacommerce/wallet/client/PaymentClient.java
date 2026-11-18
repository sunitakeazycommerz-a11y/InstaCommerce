package com.instacommerce.wallet.client;

import com.instacommerce.wallet.exception.ApiException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestInterceptor;
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

    public PaymentClient(String baseUrl) {
        this(baseUrl, "wallet-loyalty-service", "");
    }

    @Autowired
    public PaymentClient(
            @Value("${payment-service.base-url:http://localhost:8085}") String baseUrl,
            @Value("${internal.service.name:${spring.application.name}}") String serviceName,
            @Value("${internal.service.token:}") String serviceToken) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(2000));
        requestFactory.setReadTimeout(Duration.ofMillis(3000));

        ClientHttpRequestInterceptor authInterceptor = (request, body, execution) -> {
            request.getHeaders().set("X-Internal-Service", serviceName);
            request.getHeaders().set("X-Internal-Token", serviceToken);
            return execution.execute(request, body);
        };

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor(authInterceptor)
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

    @SuppressWarnings("unused")
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

        /** Returns true if the payment has been completed or captured successfully. */
        public boolean isCompleted() {
            return "CAPTURED".equalsIgnoreCase(status)
                    || "COMPLETED".equalsIgnoreCase(status);
        }
    }
}
