package com.instacommerce.mobilebff.aggregator;

import com.instacommerce.mobilebff.client.UpstreamClientFactory;
import com.instacommerce.mobilebff.config.MobileBffProperties;
import com.instacommerce.mobilebff.dto.HomeResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        boolean hasUserId = userId != null && !userId.isBlank();

        Mono<List<Map<String, Object>>> categoriesMono = executeWithCircuitBreaker(
            "catalogService",
            catalogCircuitBreaker,
            clientFactory.catalogClient()
                .get()
                .uri("/categories")
                .headers(headers -> applyUserHeader(headers, userId, hasUserId))
                .retrieve()
                .bodyToMono(LIST_MAP_TYPE),
            List.of());

        Mono<List<String>> trendingMono = executeWithCircuitBreaker(
            "searchService",
            searchCircuitBreaker,
            clientFactory.searchClient()
                .get()
                .uri("/search/trending")
                .headers(headers -> applyUserHeader(headers, userId, hasUserId))
                .retrieve()
                .bodyToMono(LIST_STRING_TYPE),
            List.of());

        Mono<Map<String, Object>> flagsMono = executeWithCircuitBreaker(
            "featureFlagService",
            featureFlagCircuitBreaker,
            clientFactory.featureFlagClient()
                .get()
                .uri(uriBuilder -> buildFeatureFlagUri(uriBuilder, userId, hasUserId))
                .headers(headers -> applyUserHeader(headers, userId, hasUserId))
                .retrieve()
                .bodyToMono(MAP_TYPE),
            Map.of());

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
                return HomeResponse.of(tuple.getT1(), tuple.getT2(), tuple.getT3(), metadata);
            })
            .onErrorResume(ex -> {
                log.error("Unhandled error aggregating home response: {}", ex.getMessage(), ex);
                return Mono.just(HomeResponse.fallback(ex.getMessage()));
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

    private void applyUserHeader(HttpHeaders headers, String userId, boolean hasUserId) {
        if (hasUserId) {
            headers.set("X-User-Id", userId);
        }
    }

    private <T> Mono<T> executeWithCircuitBreaker(
            String serviceName,
            CircuitBreaker circuitBreaker,
            Mono<T> upstreamCall,
            T fallbackValue) {
        return Mono.defer(() -> {
            if (!circuitBreaker.tryAcquirePermission()) {
                log.warn("Circuit breaker '{}' is OPEN; returning fallback in HomeAggregator", serviceName);
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
                    return Mono.just(fallbackValue);
                });
        });
    }
}
