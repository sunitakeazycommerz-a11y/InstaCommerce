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

    public MobileBffController(HomeAggregator homeAggregator) {
        this.homeAggregator = homeAggregator;
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
}
