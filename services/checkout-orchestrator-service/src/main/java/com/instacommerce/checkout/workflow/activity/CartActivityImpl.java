package com.instacommerce.checkout.workflow.activity;

import com.instacommerce.checkout.dto.CartValidationResult;
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
    public CartValidationResult validateCart(String userId) {
        log.info("Validating cart for user={}", userId);
        return restTemplate.getForObject("/api/carts/{userId}/validate", CartValidationResult.class, userId);
    }

    @Override
    public void clearCart(String userId) {
        log.info("Clearing cart for user={}", userId);
        restTemplate.delete("/api/carts/{userId}", userId);
    }
}
