package com.instacommerce.notification.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.instacommerce.notification.config.InternalServiceAuthInterceptor;
import com.instacommerce.notification.config.NotificationProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Component
public class RestOrderLookupClient implements OrderLookupClient {
    private static final Logger logger = LoggerFactory.getLogger(RestOrderLookupClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public RestOrderLookupClient(RestTemplateBuilder builder, NotificationProperties notificationProperties,
                                 @Value("${internal.service.name:${spring.application.name}}") String serviceName,
                                 @Value("${internal.service.token:dev-internal-token-change-in-prod}") String serviceToken) {
        this.restTemplate = builder
            .setConnectTimeout(Duration.ofSeconds(3))
            .setReadTimeout(Duration.ofSeconds(5))
            .additionalInterceptors(new InternalServiceAuthInterceptor(serviceName, serviceToken))
            .build();
        this.baseUrl = notificationProperties.getOrder().getBaseUrl();
    }

    @Override
    public Optional<OrderSnapshot> findOrder(UUID orderId) {
        try {
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
        } catch (RestClientException ex) {
            logger.warn("Failed to fetch order snapshot for {}", orderId, ex);
            return Optional.empty();
        }
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

