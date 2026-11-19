package com.instacommerce.mobilebff.aggregator;

import com.instacommerce.mobilebff.client.UpstreamClientFactory;
import com.instacommerce.mobilebff.config.MobileBffProperties;
import com.instacommerce.mobilebff.dto.HomeResponse;
import com.instacommerce.mobilebff.security.CorrelationIdWebFilter;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

@Component
public class HomeAggregator {

    private static final Logger log = LoggerFactory.getLogger(HomeAggregator.class);

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP_TYPE =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<String>> LIST_STRING_TYPE =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
        new ParameterizedTypeReference<>() {};

    private final UpstreamClientFactory clientFactory;
    private final Duration timeout;
    private final CircuitBreaker catalogCircuitBreaker;
    private final CircuitBreaker searchCircuitBreaker;
    private final CircuitBreaker featureFlagCircuitBreaker;
    private final CircuitBreaker identityCircuitBreaker;
    private final CircuitBreaker walletLoyaltyCircuitBreaker;

    public HomeAggregator(UpstreamClientFactory clientFactory, MobileBffProperties properties) {
        this(clientFactory, properties, CircuitBreakerRegistry.ofDefaults());
    }

    public HomeAggregator(
            UpstreamClientFactory clientFactory,
            MobileBffProperties properties,
            CircuitBreakerRegistry circuitBreakerRegistry) {
        this.clientFactory = clientFactory;
        this.timeout = Duration.ofMillis(properties.getUpstream().getTimeoutMs());
        CircuitBreakerRegistry registry = circuitBreakerRegistry != null
            ? circuitBreakerRegistry
            : CircuitBreakerRegistry.ofDefaults();
        this.catalogCircuitBreaker = registry.circuitBreaker("catalogService");
        this.searchCircuitBreaker = registry.circuitBreaker("searchService");
        this.featureFlagCircuitBreaker = registry.circuitBreaker("featureFlagService");
        this.identityCircuitBreaker = registry.circuitBreaker("identityService");
        this.walletLoyaltyCircuitBreaker = registry.circuitBreaker("walletLoyaltyService");
    }

    @Autowired
    public HomeAggregator(
            UpstreamClientFactory clientFactory,
            MobileBffProperties properties,
            ObjectProvider<CircuitBreakerRegistry> circuitBreakerRegistryProvider) {
        this(
            clientFactory,
            properties,
            circuitBreakerRegistryProvider != null
                ? circuitBreakerRegistryProvider.getIfAvailable(CircuitBreakerRegistry::ofDefaults)
                : CircuitBreakerRegistry.ofDefaults());
    }

    public Mono<HomeResponse> aggregate(String userId, String storeId) {
        return Mono.deferContextual(ctx -> {
            boolean hasUserId = userId != null && !userId.isBlank();
            String correlationId = ctx.<String>getOrEmpty(CorrelationIdWebFilter.CONTEXT_KEY).orElse(null);
            Set<String> degradedServices = ConcurrentHashMap.newKeySet();

            Mono<List<Map<String, Object>>> categoriesMono = executeWithCircuitBreaker(
                "catalogService",
                catalogCircuitBreaker,
                clientFactory.catalogClient()
                    .get()
                    .uri("/categories")
                    .headers(headers -> applyRequestHeaders(headers, userId, hasUserId, correlationId))
                    .retrieve()
                    .bodyToMono(LIST_MAP_TYPE),
                List.of(),
                degradedServices);

            Mono<List<String>> trendingMono = executeWithCircuitBreaker(
                "searchService",
                searchCircuitBreaker,
                clientFactory.searchClient()
                    .get()
                    .uri("/search/trending")
                    .headers(headers -> applyRequestHeaders(headers, userId, hasUserId, correlationId))
                    .retrieve()
                    .bodyToMono(LIST_STRING_TYPE),
                List.of(),
                degradedServices);

            Mono<Map<String, Object>> flagsMono = executeWithCircuitBreaker(
                "featureFlagService",
                featureFlagCircuitBreaker,
                clientFactory.featureFlagClient()
                    .get()
                    .uri(uriBuilder -> buildFeatureFlagUri(uriBuilder, userId, hasUserId))
                    .headers(headers -> applyRequestHeaders(headers, userId, hasUserId, correlationId))
                    .retrieve()
                    .bodyToMono(MAP_TYPE),
                Map.of(),
                degradedServices);

            return Mono.zip(categoriesMono, trendingMono, flagsMono)
                .map(tuple -> {
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("timestamp", Instant.now().toString());
                    if (storeId != null && !storeId.isBlank()) {
                        metadata.put("storeId", storeId);
                    }
                    if (hasUserId) {
                        metadata.put("userId", userId);
                    }
                    if (!degradedServices.isEmpty()) {
                        metadata.put("degraded", true);
                        metadata.put("degradedServices", degradedServices.stream().sorted().toList());
                    }
                    return HomeResponse.of(tuple.getT1(), tuple.getT2(), tuple.getT3(), metadata);
                })
                .onErrorResume(ex -> {
                    log.error("Unhandled error aggregating home response: {}", ex.getMessage(), ex);
                    return Mono.just(HomeResponse.fallback("upstream_unavailable"));
                });
        });
    }

    public Mono<Map<String, Object>> aggregateProfile(String userId) {
        return aggregateProfile(userId, null);
    }

    public Mono<Map<String, Object>> aggregateProfile(String userId, String bearerToken) {
        return Mono.deferContextual(ctx -> {
            boolean hasUserId = userId != null && !userId.isBlank() && !"anonymous".equals(userId);
            String resolvedUserId = hasUserId ? userId : "anonymous";
            if (!hasUserId) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("userId", resolvedUserId);
                result.put("status", "anonymous");
                result.put("profile", Map.of());
                result.put("wallet", Map.of());
                result.put("loyalty", Map.of());
                return Mono.just(result);
            }

            String correlationId = ctx.<String>getOrEmpty(CorrelationIdWebFilter.CONTEXT_KEY).orElse(null);
            Set<String> degradedServices = ConcurrentHashMap.newKeySet();

            Mono<Map<String, Object>> profileMono = executeWithCircuitBreaker(
                "identityService",
                identityCircuitBreaker,
                clientFactory.identityClient()
                    .get()
                    .uri("/users/{id}", userId)
                    .headers(headers -> applyRequestHeaders(headers, userId, true, correlationId))
                    .retrieve()
                    .bodyToMono(MAP_TYPE),
                Map.of(),
                degradedServices);

            Mono<Map<String, Object>> walletMono = executeWithCircuitBreaker(
                "walletLoyaltyService",
                walletLoyaltyCircuitBreaker,
                clientFactory.walletLoyaltyClient()
                    .get()
                    .uri("/wallet/balance")
                    .headers(headers -> applyProfileHeaders(headers, userId, bearerToken, correlationId))
                    .retrieve()
                    .bodyToMono(MAP_TYPE),
                Map.of(),
                degradedServices);

            Mono<Map<String, Object>> loyaltyMono = executeWithCircuitBreaker(
                "walletLoyaltyService",
                walletLoyaltyCircuitBreaker,
                clientFactory.walletLoyaltyClient()
                    .get()
                    .uri("/loyalty/points")
                    .headers(headers -> applyProfileHeaders(headers, userId, bearerToken, correlationId))
                    .retrieve()
                    .bodyToMono(MAP_TYPE),
                Map.of(),
                degradedServices);

            return Mono.zip(profileMono, walletMono, loyaltyMono)
                .map(tuple -> {
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("userId", resolvedUserId);
                    response.put("status", "authenticated");
                    response.put("profile", tuple.getT1());
                    response.put("wallet", tuple.getT2());
                    response.put("loyalty", tuple.getT3());
                    if (!degradedServices.isEmpty()) {
                        response.put("degraded", true);
                        response.put("degradedServices", degradedServices.stream().sorted().toList());
                    }
                    return response;
                })
                .onErrorResume(ex -> {
                    log.warn("Unhandled error aggregating profile for user {}: {}", resolvedUserId, ex.getMessage());
                    Map<String, Object> fallback = new LinkedHashMap<>();
                    fallback.put("userId", resolvedUserId);
                    fallback.put("status", "authenticated");
                    fallback.put("degraded", true);
                    fallback.put("profile", Map.of());
                    fallback.put("wallet", Map.of());
                    fallback.put("loyalty", Map.of());
                    return Mono.just(fallback);
                });
        });
    }

    private URI buildFeatureFlagUri(UriBuilder uriBuilder, String userId, boolean hasUserId) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUri(uriBuilder.build())
            .path("/flags");
        if (hasUserId) {
            return builder
                .queryParam("userId", "{userId}")
                .encode(StandardCharsets.UTF_8)
                .buildAndExpand(userId)
                .toUri();
        }
        return builder.build().toUri();
    }

    private void applyRequestHeaders(HttpHeaders headers, String userId, boolean hasUserId, String correlationId) {
        if (hasUserId) {
            headers.set("X-User-Id", userId);
        }
        if (correlationId != null && !correlationId.isBlank()) {
            headers.set(CorrelationIdWebFilter.HEADER_CORRELATION_ID, correlationId);
        }
    }

    private void applyProfileHeaders(HttpHeaders headers, String userId, String bearerToken, String correlationId) {
        applyRequestHeaders(headers, userId, true, correlationId);
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.setBearerAuth(bearerToken);
        }
    }

    private <T> Mono<T> executeWithCircuitBreaker(
            String serviceName,
            CircuitBreaker circuitBreaker,
            Mono<T> upstreamCall,
            T fallbackValue,
            Set<String> degradedServices) {
        return Mono.defer(() -> {
            if (!circuitBreaker.tryAcquirePermission()) {
                log.warn("Circuit breaker '{}' is OPEN; returning fallback in HomeAggregator", serviceName);
                degradedServices.add(serviceName);
                return Mono.just(fallbackValue);
            }
            long startTimestamp = circuitBreaker.getCurrentTimestamp();
            AtomicBoolean completed = new AtomicBoolean(false);
            return upstreamCall
                .timeout(timeout)
                .defaultIfEmpty(fallbackValue)
                .doOnNext(result -> {
                    if (completed.compareAndSet(false, true)) {
                        long duration = circuitBreaker.getCurrentTimestamp() - startTimestamp;
                        circuitBreaker.onResult(duration, circuitBreaker.getTimestampUnit(), result);
                    }
                })
                .doOnError(ex -> {
                    if (completed.compareAndSet(false, true)) {
                        long duration = circuitBreaker.getCurrentTimestamp() - startTimestamp;
                        circuitBreaker.onError(duration, circuitBreaker.getTimestampUnit(), ex);
                    }
                })
                .doOnCancel(() -> {
                    if (completed.compareAndSet(false, true)) {
                        circuitBreaker.releasePermission();
                    }
                })
                .onErrorResume(ex -> {
                    log.warn("{} call failed in HomeAggregator: {}", serviceName, ex.getMessage());
                    degradedServices.add(serviceName);
                    return Mono.just(fallbackValue);
                });
        });
    }
}
