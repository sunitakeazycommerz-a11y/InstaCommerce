package com.instacommerce.mobilebff.aggregator;

import com.instacommerce.mobilebff.client.UpstreamClientFactory;
import com.instacommerce.mobilebff.config.MobileBffProperties;
import com.instacommerce.mobilebff.dto.HomeResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
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

    public HomeAggregator(UpstreamClientFactory clientFactory, MobileBffProperties properties) {
        this.clientFactory = clientFactory;
        this.timeout = Duration.ofMillis(properties.getUpstream().getTimeoutMs());
    }

    public Mono<HomeResponse> aggregate(String userId, String storeId) {
        Mono<List<Map<String, Object>>> categoriesMono = clientFactory.catalogClient()
            .get()
            .uri("/categories")
            .headers(headers -> {
                if (userId != null && !userId.isBlank()) {
                    headers.set("X-User-Id", userId);
                }
            })
            .retrieve()
            .bodyToMono(LIST_MAP_TYPE)
            .timeout(timeout)
            .onErrorResume(ex -> {
                log.warn("Catalog service call failed in HomeAggregator: {}", ex.getMessage());
                return Mono.just(List.of());
            });

        Mono<List<String>> trendingMono = clientFactory.searchClient()
            .get()
            .uri("/search/trending")
            .headers(headers -> {
                if (userId != null && !userId.isBlank()) {
                    headers.set("X-User-Id", userId);
                }
            })
            .retrieve()
            .bodyToMono(LIST_STRING_TYPE)
            .timeout(timeout)
            .onErrorResume(ex -> {
                log.warn("Search service call failed in HomeAggregator: {}", ex.getMessage());
                return Mono.just(List.of());
            });

        String flagUri = (userId != null && !userId.isBlank()) ? "/flags?userId=" + userId : "/flags";
        Mono<Map<String, Object>> flagsMono = clientFactory.featureFlagClient()
            .get()
            .uri(flagUri)
            .headers(headers -> {
                if (userId != null && !userId.isBlank()) {
                    headers.set("X-User-Id", userId);
                }
            })
            .retrieve()
            .bodyToMono(MAP_TYPE)
            .timeout(timeout)
            .onErrorResume(ex -> {
                log.warn("Feature flag service call failed in HomeAggregator: {}", ex.getMessage());
                return Mono.just(Map.of());
            });

        return Mono.zip(categoriesMono, trendingMono, flagsMono)
            .map(tuple -> {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("timestamp", Instant.now().toString());
                if (storeId != null && !storeId.isBlank()) {
                    metadata.put("storeId", storeId);
                }
                if (userId != null && !userId.isBlank()) {
                    metadata.put("userId", userId);
                }
                return HomeResponse.of(tuple.getT1(), tuple.getT2(), tuple.getT3(), metadata);
            })
            .onErrorResume(ex -> {
                log.error("Unhandled error aggregating home response: {}", ex.getMessage(), ex);
                return Mono.just(HomeResponse.fallback(ex.getMessage()));
            });
    }
}
