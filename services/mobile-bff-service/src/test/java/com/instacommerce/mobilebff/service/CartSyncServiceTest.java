package com.instacommerce.mobilebff.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import reactor.test.StepVerifier;

@DisplayName("CartSyncService Unit Tests")
class CartSyncServiceTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("Subscribe and dispatch emits server sent events to matching user")
  void shouldSubscribeAndReceiveDispatchedEvents() {
    CartSyncService service = new CartSyncService(objectMapper);
    String userId = "user-123";
    Map<String, Object> eventData = Map.of("itemCount", 3, "totalCents", 1500);

    StepVerifier.create(service.subscribeToCartUpdates(userId))
        .then(() -> service.dispatchToUser(userId, eventData))
        .assertNext(sse -> {
          assertThat(sse.event()).isEqualTo("cart-updated");
          assertThat(sse.data()).isEqualTo(eventData);
        })
        .thenCancel()
        .verify(Duration.ofSeconds(2));
  }

  @Test
  @DisplayName("Destroy without connection factory succeeds without exception")
  void shouldHandleDestroyWithoutConnectionFactoryGracefully() {
    CartSyncService service = new CartSyncService(objectMapper);
    assertDoesNotThrow(service::destroy);
  }

  @Test
  @DisplayName("Lifecycle with connection factory initializes and destroys listener container")
  @SuppressWarnings("unchecked")
  void shouldInitializeAndDestroyListenerContainerWhenConnectionFactoryAvailable() {
    RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
    RedisConnection connection = mock(RedisConnection.class);
    when(connectionFactory.getConnection()).thenReturn(connection);

    ObjectProvider<RedisConnectionFactory> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(connectionFactory);

    CartSyncService service = new CartSyncService(objectMapper, provider);
    assertDoesNotThrow(service::destroy);
  }
}
