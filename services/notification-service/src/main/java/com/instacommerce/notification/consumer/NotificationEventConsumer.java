package com.instacommerce.notification.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.contracts.events.EventEnvelope;
import com.instacommerce.contracts.topics.TopicNames;
import com.instacommerce.notification.service.NotificationService;
import com.instacommerce.notification.service.PreferenceLookupException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class NotificationEventConsumer {
    private static final Logger logger = LoggerFactory.getLogger(NotificationEventConsumer.class);

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public NotificationEventConsumer(NotificationService notificationService, ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = TopicNames.ORDERS_EVENTS, groupId = "notification-service", concurrency = "3")
    public void onOrderEvent(ConsumerRecord<String, String> record) {
        handle(record, "order");
    }

    @KafkaListener(topics = TopicNames.PAYMENTS_EVENTS, groupId = "notification-service", concurrency = "3")
    public void onPaymentEvent(ConsumerRecord<String, String> record) {
        handle(record, "payment");
    }

    @KafkaListener(topics = TopicNames.FULFILLMENT_EVENTS, groupId = "notification-service", concurrency = "3")
    public void onFulfillmentEvent(ConsumerRecord<String, String> record) {
        handle(record, "fulfillment");
    }

    private void handle(ConsumerRecord<String, String> record, String domain) {
        try {
            EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
            notificationService.handleEvent(record, envelope);
        } catch (PreferenceLookupException ex) {
            logger.warn("Transient preference lookup failure for {} event at offset={} partition={}: {}",
                domain, record.offset(), record.partition(), ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            logger.error("Failed to process {} event at offset={} partition={}: {}",
                domain, record.offset(), record.partition(), ex.getMessage(), ex);
        }
    }
}
