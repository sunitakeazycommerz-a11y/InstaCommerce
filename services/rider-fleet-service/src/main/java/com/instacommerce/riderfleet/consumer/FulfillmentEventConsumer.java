package com.instacommerce.riderfleet.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.riderfleet.service.RiderAssignmentService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class FulfillmentEventConsumer {
    private static final Logger logger = LoggerFactory.getLogger(FulfillmentEventConsumer.class);

    private final RiderAssignmentService riderAssignmentService;
    private final ObjectMapper objectMapper;

    public FulfillmentEventConsumer(RiderAssignmentService riderAssignmentService, ObjectMapper objectMapper) {
        this.riderAssignmentService = riderAssignmentService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "fulfillment.events", groupId = "rider-fleet-service")
    public void onFulfillmentEvent(ConsumerRecord<String, String> record) {
        try {
            EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
            if ("OrderPacked".equals(envelope.eventType())) {
                FulfillmentEventPayload payload = objectMapper.treeToValue(
                    envelope.payload(), FulfillmentEventPayload.class);
                riderAssignmentService.assignRider(
                    payload.orderId(), payload.storeId(), payload.pickupLat(), payload.pickupLng());
                logger.info("Rider assigned for packed order={}", payload.orderId());
            }
        } catch (Exception ex) {
            logger.error("Failed to process fulfillment event key={}", record.key(), ex);
        }
    }
}
