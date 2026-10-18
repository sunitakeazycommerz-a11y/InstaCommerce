package com.instacommerce.mobilebff.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Service
public class CartSyncService {

    private static final Logger log = LoggerFactory.getLogger(CartSyncService.class);

    private final ObjectMapper objectMapper;
    private final Map<String, Sinks.Many<ServerSentEvent<Map<String, Object>>>> userSinks = new ConcurrentHashMap<>();

    @Autowired
    public CartSyncService(ObjectMapper objectMapper,
                           ObjectProvider<RedisConnectionFactory> connectionFactoryProvider) {
        this.objectMapper = objectMapper;
        RedisConnectionFactory connectionFactory = connectionFactoryProvider != null ? connectionFactoryProvider.getIfAvailable() : null;
        if (connectionFactory != null) {
            try {
                RedisMessageListenerContainer container = new RedisMessageListenerContainer();
                container.setConnectionFactory(connectionFactory);
                container.addMessageListener(new MessageListener() {
                    @Override
                    public void onMessage(Message message, byte[] pattern) {
                        try {
                            String body = new String(message.getBody());
                            Map<String, Object> data = objectMapper.readValue(body, new TypeReference<>() {});
                            String userId = (String) data.get("userId");
                            if (userId != null) {
                                dispatchToUser(userId, data);
                            }
                        } catch (Exception e) {
                            log.debug("Error deserializing cart sync pubsub message: {}", e.getMessage());
                        }
                    }
                }, new ChannelTopic("cart:events"));
                container.afterPropertiesSet();
                container.start();
                log.info("Initialized Redis Pub/Sub listener for mobile cart sync");
            } catch (Exception e) {
                log.warn("Failed to initialize Redis Pub/Sub listener in mobile-bff: {}", e.getMessage());
            }
        }
    }

    public CartSyncService(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    public Flux<ServerSentEvent<Map<String, Object>>> subscribeToCartUpdates(String userId) {
        Sinks.Many<ServerSentEvent<Map<String, Object>>> sink = userSinks.computeIfAbsent(userId, k ->
                Sinks.many().multicast().onBackpressureBuffer());
        return sink.asFlux();
    }

    public void dispatchToUser(String userId, Map<String, Object> cartEvent) {
        Sinks.Many<ServerSentEvent<Map<String, Object>>> sink = userSinks.get(userId);
        if (sink != null) {
            ServerSentEvent<Map<String, Object>> sse = ServerSentEvent.<Map<String, Object>>builder()
                    .event("cart-updated")
                    .data(cartEvent)
                    .build();
            sink.tryEmitNext(sse);
            log.debug("Dispatched cart update SSE to userId={}", userId);
        }
    }
}
