package com.instacommerce.mobilebff.controller;

import com.instacommerce.mobilebff.aggregator.HomeAggregator;
import com.instacommerce.mobilebff.dto.HomeResponse;
import com.instacommerce.mobilebff.service.CartSyncService;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping({"/bff/mobile/v1", "/m/v1"})
public class MobileBffController {

    private final HomeAggregator homeAggregator;
    private final CartSyncService cartSyncService;

    @Autowired
    public MobileBffController(
            HomeAggregator homeAggregator,
            ObjectProvider<CartSyncService> cartSyncServiceProvider) {
        this.homeAggregator = homeAggregator;
        this.cartSyncService = cartSyncServiceProvider != null ? cartSyncServiceProvider.getIfAvailable() : null;
    }

    public MobileBffController(HomeAggregator homeAggregator) {
        this(homeAggregator, null);
    }

    @GetMapping("/home")
    public Mono<HomeResponse> home(
            @RequestParam(required = false) String storeId,
            Authentication authentication) {
        String userId = isAuthenticatedUser(authentication) ? authentication.getName() : null;
        return homeAggregator.aggregate(userId, storeId);
    }

    @GetMapping("/profile")
    public Mono<Map<String, Object>> profile(Authentication authentication) {
        String userId = authentication != null ? authentication.getName() : "anonymous";
        return Mono.just(Map.of(
            "userId", userId,
            "status", "authenticated"
        ));
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
        boolean isPrivileged = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(authority -> "ROLE_ADMIN".equals(authority) || "ROLE_INTERNAL_SERVICE".equals(authority));
        if (!isPrivileged && !targetUserId.equals(authentication.getName())) {
            throw new AccessDeniedException("Access denied");
        }
    }

    private boolean isAuthenticatedUser(Authentication authentication) {
        return authentication != null
            && authentication.isAuthenticated()
            && !"anonymousUser".equals(authentication.getPrincipal());
    }
}
