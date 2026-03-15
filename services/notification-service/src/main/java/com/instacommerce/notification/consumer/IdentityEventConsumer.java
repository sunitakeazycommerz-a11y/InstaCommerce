package com.instacommerce.notification.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.contracts.events.EventEnvelope;
import com.instacommerce.contracts.topics.TopicNames;
import com.instacommerce.notification.service.UserErasureService;
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

    @KafkaListener(topics = TopicNames.IDENTITY_EVENTS, groupId = "notification-service", concurrency = "3")
    public void onIdentityEvent(ConsumerRecord<String, String> record) {
        try {
            EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
            if (!"UserErased".equals(envelope.eventType())) {
                return;
            }
            UserErasedEvent event = objectMapper.treeToValue(envelope.payload(), UserErasedEvent.class);
            if (event.userId() == null) {
                logger.warn("UserErased event missing userId");
                return;
            }
            Instant erasedAt = event.erasedAt() == null ? Instant.now() : event.erasedAt();
            userErasureService.anonymizeUser(event.userId(), erasedAt);
        } catch (Exception ex) {
            logger.error("Failed to process identity event at offset={} partition={}: {}",
                record.offset(), record.partition(), ex.getMessage(), ex);
        }
    }
}
