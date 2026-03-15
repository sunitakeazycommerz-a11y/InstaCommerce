package com.instacommerce.checkout.workflow.activity;

import com.instacommerce.checkout.dto.CartValidationResult;
import com.instacommerce.checkout.exception.ServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class CartActivityImpl implements CartActivity {
    private static final Logger log = LoggerFactory.getLogger(CartActivityImpl.class);
    private final RestTemplate restTemplate;

    public CartActivityImpl(@Qualifier("cartRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    @CircuitBreaker(name = "cartService", fallbackMethod = "validateCartFallback")
    public CartValidationResult validateCart(String userId) {
        log.info("Validating cart for user={}", userId);
        return restTemplate.getForObject("/api/carts/{userId}/validate", CartValidationResult.class, userId);
    }

    @Override
    @CircuitBreaker(name = "cartService", fallbackMethod = "clearCartFallback")
    public void clearCart(String userId) {
        log.info("Clearing cart for user={}", userId);
        restTemplate.delete("/api/carts/{userId}", userId);
    }

    private CartValidationResult validateCartFallback(String userId, Exception e) {
        log.warn("Circuit breaker fallback for cartService validateCart userId={}: {}", userId, e.getMessage());
        throw new ServiceUnavailableException("cartService",
                "Cart service unavailable for validateCart userId=" + userId, e);
    }

    private void clearCartFallback(String userId, Exception e) {
        log.warn("Circuit breaker fallback for cartService clearCart userId={}: {}", userId, e.getMessage());
        throw new ServiceUnavailableException("cartService",
                "Cart service unavailable for clearCart userId=" + userId, e);
    }
}
