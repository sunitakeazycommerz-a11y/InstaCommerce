package com.instacommerce.notification.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.contracts.topics.TopicNames;
import com.instacommerce.notification.service.NotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class FulfillmentEventConsumer {
    private static final Logger logger = LoggerFactory.getLogger(FulfillmentEventConsumer.class);

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public FulfillmentEventConsumer(NotificationService notificationService, ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = TopicNames.FULFILLMENT_EVENTS, groupId = "notification-service", concurrency = "3")
    public void onFulfillmentEvent(ConsumerRecord<String, String> record) {
        handle(record);
    }

    private void handle(ConsumerRecord<String, String> record) {
        try {
            EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
            notificationService.handleEvent(record, envelope);
        } catch (Exception ex) {
            logger.error("Failed to process fulfillment event at offset={} partition={}: {}",
                record.offset(), record.partition(), ex.getMessage(), ex);
        }
    }
}
