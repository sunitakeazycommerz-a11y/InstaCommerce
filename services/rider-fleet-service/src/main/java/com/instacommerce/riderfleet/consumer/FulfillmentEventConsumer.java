package com.instacommerce.riderfleet.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.contracts.topics.TopicNames;
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

    @KafkaListener(topics = TopicNames.FULFILLMENT_EVENTS, groupId = "rider-fleet-service")
    public void onFulfillmentEvent(ConsumerRecord<String, String> record) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        if ("OrderPacked".equals(envelope.eventType())) {
            FulfillmentEventPayload payload = objectMapper.treeToValue(
                envelope.payload(), FulfillmentEventPayload.class);
            if (payload.pickupLat() == null || payload.pickupLng() == null) {
                logger.warn("OrderPacked event for order={} missing pickup coordinates, skipping assignment",
                    payload.orderId());
                return;
            }
            riderAssignmentService.assignRider(
                payload.orderId(), payload.storeId(), payload.pickupLat(), payload.pickupLng());
            logger.info("Rider assigned for packed order={}", payload.orderId());
        }
    }
}
