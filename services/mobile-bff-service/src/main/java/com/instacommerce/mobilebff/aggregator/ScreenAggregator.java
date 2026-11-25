package com.instacommerce.mobilebff.aggregator;

import com.instacommerce.mobilebff.client.UpstreamClientFactory;
import com.instacommerce.mobilebff.config.MobileBffProperties;
import com.instacommerce.mobilebff.security.CorrelationIdWebFilter;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Component
public class ScreenAggregator {

    private static final Logger log = LoggerFactory.getLogger(ScreenAggregator.class);
    private static final Pattern SAFE_ID = Pattern.compile("^[a-zA-Z0-9._:-]{1,128}$");
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
        new ParameterizedTypeReference<>() {};

    private final UpstreamClientFactory clientFactory;
    private final Duration timeout;
    private final CircuitBreaker catalogCircuitBreaker;
    private final CircuitBreaker pricingCircuitBreaker;
    private final CircuitBreaker inventoryCircuitBreaker;
    private final CircuitBreaker cartCircuitBreaker;
    private final CircuitBreaker walletLoyaltyCircuitBreaker;
    private final CircuitBreaker orderCircuitBreaker;
    private final CircuitBreaker fulfillmentCircuitBreaker;
    private final CircuitBreaker routingEtaCircuitBreaker;
    private final CircuitBreaker checkoutCircuitBreaker;

    public ScreenAggregator(UpstreamClientFactory clientFactory, MobileBffProperties properties) {
        this(clientFactory, properties, CircuitBreakerRegistry.ofDefaults());
    }

    public ScreenAggregator(
            UpstreamClientFactory clientFactory,
            MobileBffProperties properties,
            CircuitBreakerRegistry circuitBreakerRegistry) {
        this.clientFactory = clientFactory;
        this.timeout = Duration.ofMillis(properties.getUpstream().getTimeoutMs());
        CircuitBreakerRegistry registry = circuitBreakerRegistry != null
            ? circuitBreakerRegistry
            : CircuitBreakerRegistry.ofDefaults();
        this.catalogCircuitBreaker = registry.circuitBreaker("catalogService");
        this.pricingCircuitBreaker = registry.circuitBreaker("pricingService");
        this.inventoryCircuitBreaker = registry.circuitBreaker("inventoryService");
        this.cartCircuitBreaker = registry.circuitBreaker("cartService");
        this.walletLoyaltyCircuitBreaker = registry.circuitBreaker("walletLoyaltyService");
        this.orderCircuitBreaker = registry.circuitBreaker("orderService");
        this.fulfillmentCircuitBreaker = registry.circuitBreaker("fulfillmentService");
        this.routingEtaCircuitBreaker = registry.circuitBreaker("routingEtaService");
        this.checkoutCircuitBreaker = registry.circuitBreaker("checkoutService");
    }

    @Autowired
    public ScreenAggregator(
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

    public Mono<Map<String, Object>> aggregateProduct(String productId, String storeId, String userId) {
        String validProductId = requireSafeId(productId, "productId");
        String validStoreId = optionalSafeId(storeId, "storeId");
        String normalizedUserId = normalizeUserId(userId);

        return Mono.deferContextual(ctx -> {
            String correlationId = ctx.<String>getOrEmpty(CorrelationIdWebFilter.CONTEXT_KEY).orElse(null);
            Set<String> degradedServices = ConcurrentHashMap.newKeySet();

            Mono<Map<String, Object>> productMono = executeCriticalWithCircuitBreaker(
                "catalogService",
                catalogCircuitBreaker,
                clientFactory.catalogClient()
                    .get()
                    .uri("/products/{id}", validProductId)
                    .headers(headers -> applyHeaders(headers, normalizedUserId, null, correlationId))
                    .retrieve()
                    .onStatus(status -> status.value() == 404,
                        resp -> Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found")))
                    .bodyToMono(MAP_TYPE));

            Mono<Map<String, Object>> pricingMono = executeWithCircuitBreaker(
                "pricingService",
                pricingCircuitBreaker,
                clientFactory.pricingClient()
                    .get()
                    .uri("/pricing/products/{id}", validProductId)
                    .headers(headers -> applyHeaders(headers, normalizedUserId, null, correlationId))
                    .retrieve()
                    .bodyToMono(MAP_TYPE),
                Map.of(),
                degradedServices);

            Mono<Map<String, Object>> stockMono = validStoreId == null
                ? Mono.just(Map.of())
                : executeWithCircuitBreaker(
                    "inventoryService",
                    inventoryCircuitBreaker,
                    clientFactory.inventoryClient()
                        .post()
                        .uri("/inventory/check")
                        .headers(headers -> applyHeaders(headers, normalizedUserId, null, correlationId))
                        .bodyValue(Map.of(
                            "storeId", validStoreId,
                            "items", List.of(Map.of("productId", validProductId, "quantity", 1))))
                        .retrieve()
                        .bodyToMono(MAP_TYPE),
                    Map.of(),
                    degradedServices);

            return Mono.zip(productMono, pricingMono, stockMono)
                .map(tuple -> {
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("productId", validProductId);
                    response.put("product", tuple.getT1());
                    response.put("pricing", tuple.getT2());
                    response.put("stock", tuple.getT3());
                    attachDegradationMetadata(response, degradedServices);
                    return response;
                });
        });
    }

    public Mono<Map<String, Object>> aggregateCartSummary(
            String userId,
            String bearerToken,
            String storeId,
            String couponCode) {
        String resolvedUserId = normalizeUserId(userId);
        if (resolvedUserId == null) {
            return Mono.error(new AccessDeniedException("Authenticated user required"));
        }
        String validStoreId = optionalSafeId(storeId, "storeId");

        return Mono.deferContextual(ctx -> {
            String correlationId = ctx.<String>getOrEmpty(CorrelationIdWebFilter.CONTEXT_KEY).orElse(null);
            Set<String> degradedServices = ConcurrentHashMap.newKeySet();

            Mono<Map<String, Object>> cartMono = executeWithCircuitBreaker(
                "cartService",
                cartCircuitBreaker,
                clientFactory.cartClient()
                    .get()
                    .uri("/cart")
                    .headers(headers -> applyHeaders(headers, resolvedUserId, bearerToken, correlationId))
                    .retrieve()
                    .bodyToMono(MAP_TYPE),
                Map.of("items", List.of()),
                degradedServices);

            Mono<Map<String, Object>> walletMono = executeWithCircuitBreaker(
                "walletLoyaltyService",
                walletLoyaltyCircuitBreaker,
                clientFactory.walletLoyaltyClient()
                    .get()
                    .uri("/wallet/balance")
                    .headers(headers -> applyHeaders(headers, resolvedUserId, bearerToken, correlationId))
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
                    .headers(headers -> applyHeaders(headers, resolvedUserId, bearerToken, correlationId))
                    .retrieve()
                    .bodyToMono(MAP_TYPE),
                Map.of(),
                degradedServices);

            return Mono.zip(cartMono, walletMono, loyaltyMono)
                .flatMap(tuple -> {
                    Map<String, Object> cart = tuple.getT1();
                    List<Map<String, Object>> pricingItems = extractPricingItems(cart);
                    String effectiveStoreId = validStoreId != null
                        ? validStoreId
                        : (cart.get("storeId") != null ? String.valueOf(cart.get("storeId")) : null);

                    Mono<Map<String, Object>> pricingMono;
                    if (pricingItems.isEmpty()) {
                        pricingMono = Mono.just(Map.of());
                    } else {
                        Map<String, Object> pricingRequest = new LinkedHashMap<>();
                        pricingRequest.put("items", pricingItems);
                        pricingRequest.put("userId", resolvedUserId);
                        if (couponCode != null && !couponCode.isBlank()) {
                            pricingRequest.put("couponCode", couponCode.trim());
                        }
                        if (effectiveStoreId != null && !effectiveStoreId.isBlank()) {
                            pricingRequest.put("storeId", effectiveStoreId);
                        }
                        pricingMono = executeWithCircuitBreaker(
                            "pricingService",
                            pricingCircuitBreaker,
                            clientFactory.pricingClient()
                                .post()
                                .uri("/pricing/calculate")
                                .headers(headers -> applyHeaders(headers, resolvedUserId, bearerToken, correlationId))
                                .bodyValue(pricingRequest)
                                .retrieve()
                                .bodyToMono(MAP_TYPE),
                            Map.of(),
                            degradedServices);
                    }

                    return pricingMono.map(pricing -> {
                        Map<String, Object> response = new LinkedHashMap<>();
                        response.put("userId", resolvedUserId);
                        response.put("cart", cart);
                        response.put("pricing", pricing);
                        response.put("wallet", tuple.getT2());
                        response.put("loyalty", tuple.getT3());
                        attachDegradationMetadata(response, degradedServices);
                        return response;
                    });
                });
        });
    }

    public Mono<Map<String, Object>> aggregateOrderTracking(
            String orderId,
            String callerUserId,
            String bearerToken,
            boolean privileged) {
        String validOrderId = requireSafeId(orderId, "orderId");
        String resolvedUserId = normalizeUserId(callerUserId);
        if (resolvedUserId == null) {
            return Mono.error(new AccessDeniedException("Authenticated user required"));
        }

        return Mono.deferContextual(ctx -> {
            String correlationId = ctx.<String>getOrEmpty(CorrelationIdWebFilter.CONTEXT_KEY).orElse(null);
            Set<String> degradedServices = ConcurrentHashMap.newKeySet();

            Mono<Map<String, Object>> orderMono = executeCriticalWithCircuitBreaker(
                "orderService",
                orderCircuitBreaker,
                clientFactory.orderClient()
                    .get()
                    .uri("/orders/{id}", validOrderId)
                    .headers(headers -> applyHeaders(headers, resolvedUserId, bearerToken, correlationId))
                    .retrieve()
                    .onStatus(status -> status.value() == 404,
                        resp -> Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found")))
                    .bodyToMono(MAP_TYPE));

            return orderMono.flatMap(order -> {
                Object ownerObj = order.get("userId");
                if (!privileged && (ownerObj == null || !resolvedUserId.equals(String.valueOf(ownerObj)))) {
                    return Mono.error(new AccessDeniedException("Access denied"));
                }

                Mono<Map<String, Object>> fulfillmentMono = executeWithCircuitBreaker(
                    "fulfillmentService",
                    fulfillmentCircuitBreaker,
                    clientFactory.fulfillmentClient()
                        .get()
                        .uri("/orders/{orderId}/tracking", validOrderId)
                        .headers(headers -> applyHeaders(headers, resolvedUserId, bearerToken, correlationId))
                        .retrieve()
                        .bodyToMono(MAP_TYPE),
                    Map.of(),
                    degradedServices);

                Mono<Map<String, Object>> deliveryMono = executeWithCircuitBreaker(
                    "routingEtaService",
                    routingEtaCircuitBreaker,
                    clientFactory.routingEtaClient()
                        .get()
                        .uri("/deliveries/{orderId}", validOrderId)
                        .headers(headers -> applyHeaders(headers, resolvedUserId, bearerToken, correlationId))
                        .retrieve()
                        .bodyToMono(MAP_TYPE),
                    Map.of(),
                    degradedServices);

                Mono<Map<String, Object>> etaMono = executeWithCircuitBreaker(
                    "routingEtaService",
                    routingEtaCircuitBreaker,
                    clientFactory.routingEtaClient()
                        .get()
                        .uri("/deliveries/{id}/eta", validOrderId)
                        .headers(headers -> applyHeaders(headers, resolvedUserId, bearerToken, correlationId))
                        .retrieve()
                        .bodyToMono(MAP_TYPE),
                    Map.of(),
                    degradedServices);

                return Mono.zip(fulfillmentMono, deliveryMono, etaMono)
                    .map(tuple -> {
                        Map<String, Object> response = new LinkedHashMap<>();
                        response.put("orderId", validOrderId);
                        response.put("order", order);
                        response.put("fulfillment", tuple.getT1());
                        response.put("delivery", tuple.getT2());
                        response.put("eta", tuple.getT3());
                        attachDegradationMetadata(response, degradedServices);
                        return response;
                    });
            });
        });
    }

    public Mono<Map<String, Object>> submitCheckout(
            Map<String, Object> body,
            String idempotencyKey,
            String callerUserId,
            String bearerToken,
            boolean privileged) {
        String resolvedUserId = normalizeUserId(callerUserId);
        if (resolvedUserId == null) {
            return Mono.error(new AccessDeniedException("Authenticated user required"));
        }

        Map<String, Object> payload = new LinkedHashMap<>(body != null ? body : Map.of());
        Object requestedUserIdObj = payload.get("userId");
        String requestedUserId = requestedUserIdObj != null ? String.valueOf(requestedUserIdObj).trim() : "";
        if (!privileged && !requestedUserId.isEmpty() && !requestedUserId.equals(resolvedUserId)) {
            return Mono.error(new AccessDeniedException("Cannot submit checkout for another user"));
        }
        payload.put("userId", (privileged && !requestedUserId.isEmpty()) ? requestedUserId : resolvedUserId);

        return Mono.deferContextual(ctx -> {
            String correlationId = ctx.<String>getOrEmpty(CorrelationIdWebFilter.CONTEXT_KEY).orElse(null);

            return executeCriticalWithCircuitBreaker(
                "checkoutService",
                checkoutCircuitBreaker,
                clientFactory.checkoutClient()
                    .post()
                    .uri("/checkout")
                    .headers(headers -> {
                        applyHeaders(headers, resolvedUserId, bearerToken, correlationId);
                        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                            headers.set("Idempotency-Key", idempotencyKey.trim());
                        }
                    })
                    .bodyValue(payload)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError,
                        resp -> resp.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(msg -> Mono.error(new ResponseStatusException(
                                resp.statusCode(),
                                msg.isBlank() ? "Checkout request rejected" : msg))))
                    .bodyToMono(MAP_TYPE));
        });
    }

    private <T> Mono<T> executeWithCircuitBreaker(
            String serviceName,
            CircuitBreaker circuitBreaker,
            Mono<T> upstreamCall,
            T fallbackValue,
            Set<String> degradedServices) {
        return Mono.defer(() -> {
            if (!circuitBreaker.tryAcquirePermission()) {
                log.warn("Circuit breaker '{}' is OPEN in ScreenAggregator", serviceName);
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
                    log.warn("{} call failed in ScreenAggregator: {}", serviceName, ex.getMessage());
                    degradedServices.add(serviceName);
                    return Mono.just(fallbackValue);
                });
        });
    }

    private <T> Mono<T> executeCriticalWithCircuitBreaker(
            String serviceName,
            CircuitBreaker circuitBreaker,
            Mono<T> upstreamCall) {
        return Mono.defer(() -> {
            if (!circuitBreaker.tryAcquirePermission()) {
                return Mono.error(new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    serviceName + " circuit breaker open"));
            }
            long startTimestamp = circuitBreaker.getCurrentTimestamp();
            AtomicBoolean completed = new AtomicBoolean(false);
            return upstreamCall
                .timeout(timeout)
                .doOnNext(result -> {
                    if (completed.compareAndSet(false, true)) {
                        long duration = circuitBreaker.getCurrentTimestamp() - startTimestamp;
                        circuitBreaker.onResult(duration, circuitBreaker.getTimestampUnit(), result);
                    }
                })
                .doOnError(ex -> {
                    if (completed.compareAndSet(false, true)) {
                        if (isClientError(ex)) {
                            circuitBreaker.releasePermission();
                        } else {
                            long duration = circuitBreaker.getCurrentTimestamp() - startTimestamp;
                            circuitBreaker.onError(duration, circuitBreaker.getTimestampUnit(), ex);
                        }
                    }
                })
                .doOnCancel(() -> {
                    if (completed.compareAndSet(false, true)) {
                        circuitBreaker.releasePermission();
                    }
                })
                .onErrorMap(ex -> {
                    if (ex instanceof ResponseStatusException
                            || ex instanceof AccessDeniedException
                            || ex instanceof IllegalArgumentException) {
                        return ex;
                    }
                    if (ex instanceof CallNotPermittedException) {
                        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, serviceName + " unavailable");
                    }
                    if (ex instanceof WebClientResponseException webEx) {
                        return new ResponseStatusException(
                            webEx.getStatusCode(),
                            serviceName + " returned " + webEx.getStatusCode().value());
                    }
                    return new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        serviceName + " request failed",
                        ex);
                });
        });
    }

    private static boolean isClientError(Throwable ex) {
        if (ex instanceof ResponseStatusException rse) {
            return rse.getStatusCode().is4xxClientError();
        }
        if (ex instanceof WebClientResponseException wcre) {
            return wcre.getStatusCode().is4xxClientError();
        }
        return false;
    }

    private static List<Map<String, Object>> extractPricingItems(Map<String, Object> cart) {
        Object itemsObj = cart != null ? cart.get("items") : null;
        if (!(itemsObj instanceof List<?> rawItems) || rawItems.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object rawItem : rawItems) {
            if (rawItem instanceof Map<?, ?> itemMap) {
                Object productId = itemMap.get("productId");
                Object quantity = itemMap.get("quantity");
                if (productId != null) {
                    int qty = quantity instanceof Number n ? Math.max(1, n.intValue()) : 1;
                    result.add(Map.of("productId", String.valueOf(productId), "quantity", qty));
                }
            }
        }
        return result;
    }

    private static void attachDegradationMetadata(Map<String, Object> response, Set<String> degradedServices) {
        if (!degradedServices.isEmpty()) {
            response.put("degraded", true);
            response.put("degradedServices", degradedServices.stream().sorted().toList());
        }
    }

    private static String requireSafeId(String rawId, String fieldName) {
        if (rawId == null || rawId.isBlank() || !SAFE_ID.matcher(rawId.trim()).matches()) {
            throw new IllegalArgumentException("Invalid " + fieldName + " format");
        }
        return rawId.trim();
    }

    private static String optionalSafeId(String rawId, String fieldName) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        return requireSafeId(rawId, fieldName);
    }

    private static String normalizeUserId(String userId) {
        if (userId == null) {
            return null;
        }
        String trimmed = userId.trim();
        if (trimmed.isEmpty()
                || "anonymous".equalsIgnoreCase(trimmed)
                || "anonymousUser".equalsIgnoreCase(trimmed)) {
            return null;
        }
        return trimmed;
    }

    private static void applyHeaders(HttpHeaders headers, String userId, String bearerToken, String correlationId) {
        if (userId != null) {
            headers.set("X-User-Id", userId);
        }
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.setBearerAuth(bearerToken);
        }
        if (correlationId != null && !correlationId.isBlank()) {
            headers.set(CorrelationIdWebFilter.HEADER_CORRELATION_ID, correlationId);
        }
    }
}
