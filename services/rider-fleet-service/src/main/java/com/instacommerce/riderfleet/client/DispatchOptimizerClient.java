package com.instacommerce.riderfleet.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
        @JsonProperty("lat") double lat,
        @JsonProperty("lng") double lng
    ) {}

    public record RiderState(
        @JsonProperty("id") String id,
        @JsonProperty("position") Position position,
        @JsonProperty("active_orders") int activeOrders,
        @JsonProperty("vehicle_type") String vehicleType,
        @JsonProperty("is_available") boolean isAvailable,
        @JsonProperty("total_deliveries") int totalDeliveries,
        @JsonProperty("avg_rating") double avgRating,
        @JsonProperty("consecutive_deliveries") int consecutiveDeliveries,
        @JsonProperty("avg_speed_kmh") double avgSpeedKmh,
        @JsonProperty("battery_percent") double batteryPercent,
        @JsonProperty("zone") String zone
    ) {
        public RiderState(String id, Position position, int activeOrders, String vehicleType, boolean isAvailable) {
            this(id, position, activeOrders, vehicleType, isAvailable, 0, 5.0, 0, 22.0, 100.0, null);
        }
    }

    public record OrderRequest(
        @JsonProperty("id") String id,
        @JsonProperty("position") Position position
    ) {}

    public record OptimizationRequest(
        @JsonProperty("riders") List<RiderState> riders,
        @JsonProperty("orders") List<OrderRequest> orders
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Assignment(
        @JsonProperty("rider_id") String riderId,
        @JsonProperty("order_ids") List<String> orderIds,
        @JsonProperty("estimated_eta_minutes") Double estimatedEtaMinutes
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OptimizationResult(
        @JsonProperty("assignments") List<Assignment> assignments,
        @JsonProperty("unassigned_orders") List<String> unassignedOrders
    ) {}
}
