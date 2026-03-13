package com.instacommerce.fulfillment.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.fulfillment.service.UserErasureService;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class IdentityEventConsumer {
    private static final Logger logger = LoggerFactory.getLogger(IdentityEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final UserErasureService userErasureService;

    public IdentityEventConsumer(ObjectMapper objectMapper, UserErasureService userErasureService) {
        this.objectMapper = objectMapper;
        this.userErasureService = userErasureService;
    }

    @KafkaListener(topics = "identity.events", groupId = "fulfillment-service-erasure")
    public void onIdentityEvent(ConsumerRecord<String, String> record) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        if (!"UserErased".equals(envelope.eventType())) {
            return;
        }
        UserErasedEvent event = objectMapper.treeToValue(envelope.payload(), UserErasedEvent.class);
        if (event.userId() == null) {
            throw new IllegalArgumentException("UserErased event missing userId, key=" + record.key());
        }
        Instant erasedAt = event.erasedAt() == null ? Instant.now() : event.erasedAt();
        userErasureService.anonymizeUser(event.userId(), erasedAt);
        logger.info("User erasure completed for identity event key={}", record.key());
    }
}
