package com.instacommerce.riderfleet.client;

import com.instacommerce.riderfleet.config.RiderFleetProperties;
import com.instacommerce.riderfleet.security.InternalServiceAuthInterceptor;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
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

    @SuppressWarnings("unused")
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

    public record Position(
        @com.fasterxml.jackson.annotation.JsonProperty("lat") double lat,
        @com.fasterxml.jackson.annotation.JsonProperty("lng") double lng
    ) {}

    public record RiderState(
        @com.fasterxml.jackson.annotation.JsonProperty("id") String id,
        @com.fasterxml.jackson.annotation.JsonProperty("position") Position position,
        @com.fasterxml.jackson.annotation.JsonProperty("active_orders") int activeOrders,
        @com.fasterxml.jackson.annotation.JsonProperty("vehicle_type") String vehicleType,
        @com.fasterxml.jackson.annotation.JsonProperty("is_available") boolean isAvailable
    ) {}

    public record OrderRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("id") String id,
        @com.fasterxml.jackson.annotation.JsonProperty("position") Position position
    ) {}

    public record OptimizationRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("riders") List<RiderState> riders,
        @com.fasterxml.jackson.annotation.JsonProperty("orders") List<OrderRequest> orders
    ) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Assignment(
        @com.fasterxml.jackson.annotation.JsonProperty("rider_id") String riderId,
        @com.fasterxml.jackson.annotation.JsonProperty("order_ids") List<String> orderIds,
        @com.fasterxml.jackson.annotation.JsonProperty("estimated_eta_minutes") Double estimatedEtaMinutes
    ) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record OptimizationResult(
        @com.fasterxml.jackson.annotation.JsonProperty("assignments") List<Assignment> assignments,
        @com.fasterxml.jackson.annotation.JsonProperty("unassigned_orders") List<String> unassignedOrders
    ) {}
}
