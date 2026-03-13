package com.instacommerce.riderfleet.client;

import com.instacommerce.riderfleet.config.RiderFleetProperties;
import com.instacommerce.riderfleet.security.InternalServiceAuthInterceptor;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class DispatchOptimizerClient {
    private static final Logger logger = LoggerFactory.getLogger(DispatchOptimizerClient.class);
    private final RestClient restClient;
    private final String baseUrl;

    public DispatchOptimizerClient(RestClient.Builder builder, RiderFleetProperties properties,
                                   @Value("${internal.service.name:${spring.application.name}}") String serviceName,
                                   @Value("${internal.service.token}") String serviceToken) {
        this.restClient = builder
            .requestFactory(clientHttpRequestFactory(Duration.ofSeconds(2), Duration.ofSeconds(5)))
            .requestInterceptor(new InternalServiceAuthInterceptor(serviceName, serviceToken))
            .build();
        this.baseUrl = properties.getDispatch().getOptimizerBaseUrl();
    }

    @CircuitBreaker(name = "dispatchOptimizer", fallbackMethod = "requestAssignmentFallback")
    public Optional<OptimizationResult> requestAssignment(OptimizationRequest request) {
        try {
            OptimizationResult result = restClient.post()
                .uri(baseUrl + "/v2/optimize/assign")
                .body(request)
                .retrieve()
                .body(OptimizationResult.class);
            return Optional.ofNullable(result);
        } catch (Exception ex) {
            logger.error("Dispatch optimizer request failed: {}", ex.getMessage(), ex);
            return Optional.empty();
        }
    }

    private Optional<OptimizationResult> requestAssignmentFallback(OptimizationRequest request, Exception ex) {
        logger.warn("Dispatch optimizer circuit breaker fallback triggered: {}", ex.getMessage());
        return Optional.empty();
    }

    private SimpleClientHttpRequestFactory clientHttpRequestFactory(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    public record RiderState(UUID riderId, BigDecimal lat, BigDecimal lng, BigDecimal rating,
                             int currentOrders, String vehicleType, boolean isAvailable) {}

    public record OrderRequest(UUID orderId, UUID storeId, BigDecimal pickupLat, BigDecimal pickupLng,
                               BigDecimal dropoffLat, BigDecimal dropoffLng) {}

    public record OptimizationRequest(List<RiderState> riders, List<OrderRequest> orders) {}

    public record Assignment(UUID orderId, UUID riderId, int estimatedMinutes) {}

    public record OptimizationResult(List<Assignment> assignments, List<String> unassignedOrders) {}
}
