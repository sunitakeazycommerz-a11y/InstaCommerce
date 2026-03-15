package com.instacommerce.notification.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.instacommerce.notification.config.InternalServiceAuthInterceptor;
import com.instacommerce.notification.config.NotificationProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class RestOrderLookupClient implements OrderLookupClient {
    private static final Logger logger = LoggerFactory.getLogger(RestOrderLookupClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public RestOrderLookupClient(NotificationProperties notificationProperties,
                                 @Value("${internal.service.name:${spring.application.name}}") String serviceName,
                                 @Value("${internal.service.token}") String serviceToken) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.restTemplate = new RestTemplate(requestFactory);
        this.restTemplate.getInterceptors().add(new InternalServiceAuthInterceptor(serviceName, serviceToken));
        this.baseUrl = notificationProperties.getOrder().getBaseUrl();
    }

    @Override
    @CircuitBreaker(name = "orderService", fallbackMethod = "findOrderFallback")
    @Retry(name = "orderService")
    public Optional<OrderSnapshot> findOrder(UUID orderId) {
        OrderResponse response = restTemplate.getForObject(
            baseUrl + "/admin/orders/" + orderId, OrderResponse.class);
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
    }

    private Optional<OrderSnapshot> findOrderFallback(UUID orderId, Exception e) {
        logger.warn("Circuit breaker fallback for orderService findOrder orderId={}: {}", orderId, e.getMessage());
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
