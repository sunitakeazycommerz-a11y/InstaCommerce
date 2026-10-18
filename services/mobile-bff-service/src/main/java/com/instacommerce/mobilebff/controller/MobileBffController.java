package com.instacommerce.mobilebff.controller;

import com.instacommerce.mobilebff.aggregator.HomeAggregator;
import com.instacommerce.mobilebff.dto.HomeResponse;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping({"/bff/mobile/v1", "/m/v1"})
public class MobileBffController {

    private final HomeAggregator homeAggregator;
    private final com.instacommerce.mobilebff.service.CartSyncService cartSyncService;

    @org.springframework.beans.factory.annotation.Autowired
    public MobileBffController(HomeAggregator homeAggregator,
                               org.springframework.beans.factory.ObjectProvider<com.instacommerce.mobilebff.service.CartSyncService> cartSyncServiceProvider) {
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
        String userId = (authentication != null && authentication.isAuthenticated() && !"anonymousUser".equals(authentication.getPrincipal()))
            ? authentication.getName() : null;
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

    @GetMapping(value = "/cart/stream/{userId}", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public reactor.core.publisher.Flux<org.springframework.http.codec.ServerSentEvent<Map<String, Object>>> streamCartUpdates(
            @org.springframework.web.bind.annotation.PathVariable String userId) {
        if (cartSyncService == null) {
            return reactor.core.publisher.Flux.empty();
        }
        return cartSyncService.subscribeToCartUpdates(userId);
    }
}
