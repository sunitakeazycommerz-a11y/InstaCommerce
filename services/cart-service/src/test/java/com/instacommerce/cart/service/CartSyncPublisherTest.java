package com.instacommerce.cart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.cart.dto.response.CartResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collections;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CartSyncPublisherTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private CartSyncPublisher publisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        publisher = new CartSyncPublisher(redisTemplate, objectMapper);
    }

    @Test
    @DisplayName("Publishes cart:updated:{userId} and cart:events to Redis Pub/Sub on cart update")
    void shouldPublishCartUpdatedEvent() {
        UUID userId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        CartResponse cart = new CartResponse(cartId, userId, Collections.emptyList(), 2500L, 2, null, 1L);

        publisher.publishCartUpdated(userId, cart);

        verify(redisTemplate).convertAndSend(eq("cart:updated:" + userId), contains(userId.toString()));
        verify(redisTemplate).convertAndSend(eq("cart:events"), contains(cartId.toString()));
    }

    @Test
    @DisplayName("Handles null redisTemplate gracefully without throwing exception")
    void shouldHandleNullRedisTemplate() {
        CartSyncPublisher nullPublisher = new CartSyncPublisher((StringRedisTemplate) null, objectMapper);
        UUID userId = UUID.randomUUID();
        CartResponse cart = new CartResponse(UUID.randomUUID(), userId, Collections.emptyList(), 0L, 0, null, 0L);

        nullPublisher.publishCartUpdated(userId, cart);
        verifyNoInteractions(redisTemplate);
    }
}
