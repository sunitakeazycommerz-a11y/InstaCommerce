package com.instacommerce.cart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.cart.dto.response.CartResponse;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class CartSyncPublisher {

    private static final Logger log = LoggerFactory.getLogger(CartSyncPublisher.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public CartSyncPublisher(ObjectProvider<StringRedisTemplate> redisTemplateProvider, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplateProvider != null ? redisTemplateProvider.getIfAvailable() : null;
        this.objectMapper = objectMapper;
    }

    public CartSyncPublisher(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void publishCartUpdated(UUID userId, CartResponse cart) {
        if (redisTemplate == null || userId == null) {
            return;
        }
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("userId", userId.toString());
            payload.put("cartId", cart.cartId() != null ? cart.cartId().toString() : "");
            payload.put("itemCount", cart.itemCount());
            payload.put("subtotalCents", cart.subtotalCents());
            payload.put("version", cart.version());
            payload.put("updatedAt", Instant.now().toString());

            String json = objectMapper.writeValueAsString(payload);

            // Channel per user for direct device subscriptions
            redisTemplate.convertAndSend("cart:updated:" + userId, json);

            // Global fanout topic for edge BFF / mobile-bff gateway
            redisTemplate.convertAndSend("cart:events", json);

            log.debug("Published cart update to Redis Pub/Sub for userId={}", userId);
        } catch (Exception e) {
            log.warn("Failed to publish cart update to Redis Pub/Sub for userId={}: {}", userId, e.getMessage());
        }
    }
}
