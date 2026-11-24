package com.instacommerce.mobilebff.controller;

import com.instacommerce.mobilebff.aggregator.HomeAggregator;
import com.instacommerce.mobilebff.aggregator.ScreenAggregator;
import com.instacommerce.mobilebff.dto.HomeResponse;
import com.instacommerce.mobilebff.service.CartSyncService;
import com.instacommerce.platform.security.SecurityRoleUtils;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping({"/bff/mobile/v1", "/m/v1"})
public class MobileBffController {

    private static final Pattern SAFE_STORE_ID = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private final HomeAggregator homeAggregator;
    private final CartSyncService cartSyncService;
    private final ScreenAggregator screenAggregator;

    @Autowired
    public MobileBffController(
            HomeAggregator homeAggregator,
            ObjectProvider<CartSyncService> cartSyncServiceProvider,
            ObjectProvider<ScreenAggregator> screenAggregatorProvider) {
        this.homeAggregator = homeAggregator;
        this.cartSyncService = cartSyncServiceProvider != null ? cartSyncServiceProvider.getIfAvailable() : null;
        this.screenAggregator = screenAggregatorProvider != null ? screenAggregatorProvider.getIfAvailable() : null;
    }

    public MobileBffController(
            HomeAggregator homeAggregator,
            ObjectProvider<CartSyncService> cartSyncServiceProvider) {
        this(homeAggregator, cartSyncServiceProvider, null);
    }

    public MobileBffController(HomeAggregator homeAggregator) {
        this(homeAggregator, null, null);
    }

    @GetMapping("/home")
    public Mono<HomeResponse> home(
            @RequestParam(required = false) String storeId,
            Authentication authentication) {
        String normalizedStoreId = sanitizeStoreId(storeId);
        String userId = isAuthenticatedUser(authentication) ? authentication.getName() : null;
        return homeAggregator.aggregate(userId, normalizedStoreId);
    }

    @GetMapping("/products/{id}")
    public Mono<Map<String, Object>> productDetails(
            @PathVariable String id,
            @RequestParam(required = false) String storeId,
            Authentication authentication) {
        if (screenAggregator == null) {
            return Mono.just(Map.of("productId", id));
        }
        String userId = isAuthenticatedUser(authentication) ? authentication.getName() : null;
        return screenAggregator.aggregateProduct(id, sanitizeStoreId(storeId), userId);
    }

    @GetMapping("/cart/summary")
    public Mono<Map<String, Object>> cartSummary(
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String couponCode,
            Authentication authentication) {
        if (!isAuthenticatedUser(authentication)) {
            return Mono.error(new AccessDeniedException("Authentication required"));
        }
        if (screenAggregator == null) {
            return Mono.just(Map.of("userId", authentication.getName()));
        }
        return screenAggregator.aggregateCartSummary(
                authentication.getName(),
                extractBearerToken(authentication),
                sanitizeStoreId(storeId),
                couponCode);
    }

    @GetMapping("/orders/{id}/tracking")
    public Mono<Map<String, Object>> orderTracking(
            @PathVariable String id,
            Authentication authentication) {
        if (!isAuthenticatedUser(authentication)) {
            return Mono.error(new AccessDeniedException("Authentication required"));
        }
        if (screenAggregator == null) {
            return Mono.just(Map.of("orderId", id));
        }
        return screenAggregator.aggregateOrderTracking(
                id,
                authentication.getName(),
                extractBearerToken(authentication),
                isPrivileged(authentication));
    }

    @PostMapping("/checkout/submit")
    public Mono<Map<String, Object>> submitCheckout(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        if (!isAuthenticatedUser(authentication)) {
            return Mono.error(new AccessDeniedException("Authentication required"));
        }
        if (screenAggregator == null) {
            return Mono.just(Map.of("status", "unavailable"));
        }
        return screenAggregator.submitCheckout(
                body,
                idempotencyKey,
                authentication.getName(),
                extractBearerToken(authentication),
                isPrivileged(authentication));
    }

    private String sanitizeStoreId(String storeId) {
        if (storeId == null || storeId.isBlank()) {
            return null;
        }
        String trimmed = storeId.trim();
        if (!SAFE_STORE_ID.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid storeId format");
        }
        return trimmed;
    }

    @GetMapping("/profile")
    public Mono<Map<String, Object>> profile(Authentication authentication) {
        String userId = isAuthenticatedUser(authentication) ? authentication.getName() : "anonymous";
        return homeAggregator.aggregateProfile(userId, extractBearerToken(authentication));
    }

    @GetMapping(value = "/cart/stream/{userId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> streamCartUpdates(
            @PathVariable String userId,
            Authentication authentication) {
        enforceCartStreamAccess(userId, authentication);
        if (cartSyncService == null) {
            return Flux.empty();
        }
        return cartSyncService.subscribeToCartUpdates(userId);
    }

    private void enforceCartStreamAccess(String targetUserId, Authentication authentication) {
        if (!isAuthenticatedUser(authentication) || targetUserId == null || targetUserId.isBlank()) {
            throw new AccessDeniedException("Access denied");
        }
        if (!isPrivileged(authentication) && !targetUserId.equals(authentication.getName())) {
            throw new AccessDeniedException("Access denied");
        }
    }

    private static boolean isPrivileged(Authentication authentication) {
        return SecurityRoleUtils.isPrivileged(authentication)
                || SecurityRoleUtils.hasRole(authentication, "ROLE_SUPER_ADMIN");
    }

    private static String extractBearerToken(Authentication authentication) {
        return authentication != null && authentication.getCredentials() instanceof String token
                ? token
                : null;
    }

    private boolean isAuthenticatedUser(Authentication authentication) {
        return authentication != null
            && authentication.isAuthenticated()
            && !"anonymousUser".equals(authentication.getPrincipal());
    }
}
