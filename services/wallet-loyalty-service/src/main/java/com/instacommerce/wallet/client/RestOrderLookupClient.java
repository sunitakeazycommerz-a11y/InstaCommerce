package com.instacommerce.wallet.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
public class RestOrderLookupClient implements OrderLookupClient {
    private static final Logger log = LoggerFactory.getLogger(RestOrderLookupClient.class);

    private final RestClient restClient;

    public RestOrderLookupClient(
            @Value("${order-service.base-url:http://order-service:8080}") String baseUrl,
            @Value("${internal.service.name:${spring.application.name}}") String serviceName,
            @Value("${internal.service.token}") String serviceToken) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));

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

    @Override
    @CircuitBreaker(name = "orderService", fallbackMethod = "findOrderFallback")
    @Retry(name = "orderService")
    public Optional<OrderSnapshot> findOrder(UUID orderId) {
        try {
            OrderResponse response = restClient.get()
                .uri("/admin/orders/{orderId}", orderId)
                .retrieve()
                .body(OrderResponse.class);
            if (response == null || response.id() == null) {
                return Optional.empty();
            }
            return Optional.of(new OrderSnapshot(
                response.id(),
                response.userId(),
                response.totalCents(),
                response.currency(),
                response.status(),
                response.createdAt()));
        } catch (HttpClientErrorException.NotFound ex) {
            log.info("Order {} not found in order-service (404)", orderId);
            return Optional.empty();
        }
    }

    private Optional<OrderSnapshot> findOrderFallback(UUID orderId, Exception e) {
        log.warn("Circuit breaker fallback for orderService findOrder orderId={}: {}", orderId, e.getMessage());
        return Optional.empty();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OrderResponse(
        UUID id,
        UUID userId,
        String status,
        long totalCents,
        String currency,
        Instant createdAt
    ) {
    }
}
