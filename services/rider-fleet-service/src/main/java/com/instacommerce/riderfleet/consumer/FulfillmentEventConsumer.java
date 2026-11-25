package com.instacommerce.riderfleet.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.contracts.events.EventEnvelope;
import com.instacommerce.contracts.topics.TopicNames;
import com.instacommerce.riderfleet.service.RiderAssignmentService;
import java.util.UUID;
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

    @KafkaListener(topics = {TopicNames.FULFILLMENT_EVENTS, TopicNames.ORDERS_EVENTS}, groupId = "rider-fleet-service")
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
            if (payload.orderWeightGrams() != null) {
                riderAssignmentService.assignRider(
                    payload.orderId(), payload.storeId(),
                    payload.pickupLat(), payload.pickupLng(),
                    payload.dropoffLat(), payload.dropoffLng(),
                    payload.orderWeightGrams());
            } else if (payload.dropoffLat() != null || payload.dropoffLng() != null) {
                riderAssignmentService.assignRider(
                    payload.orderId(), payload.storeId(),
                    payload.pickupLat(), payload.pickupLng(),
                    payload.dropoffLat(), payload.dropoffLng());
            } else {
                riderAssignmentService.assignRider(
                    payload.orderId(), payload.storeId(), payload.pickupLat(), payload.pickupLng());
            }
            logger.info("Rider assigned for packed order={}", payload.orderId());
        } else if ("OrderDelivered".equals(envelope.eventType()) || "DeliveryCompleted".equals(envelope.eventType())) {
            UUID orderId = resolveOrderId(envelope);
            if (orderId != null) {
                long tipCents = resolveTipCents(envelope);
                if (tipCents > 0) {
                    riderAssignmentService.completeDelivery(orderId, tipCents);
                } else {
                    riderAssignmentService.completeDelivery(orderId);
                }
                logger.info("Rider delivery completed for order={}", orderId);
            }
        } else if ("OrderCancelled".equals(envelope.eventType())
                || "DeliveryFailed".equals(envelope.eventType())
                || "PickTaskCancelled".equals(envelope.eventType())) {
            UUID orderId = resolveOrderId(envelope);
            if (orderId != null) {
                riderAssignmentService.cancelAssignment(orderId, envelope.eventType());
                logger.info("Rider assignment cancelled for order={}", orderId);
            }
        }
    }

    private UUID resolveOrderId(EventEnvelope envelope) {
        if (envelope.payload() != null && !envelope.payload().isNull() && envelope.payload().has("orderId")) {
            String rawOrderId = envelope.payload().get("orderId").asText();
            try {
                return UUID.fromString(rawOrderId);
            } catch (IllegalArgumentException ex) {
                logger.warn("Invalid orderId '{}' in payload for eventType={}", rawOrderId, envelope.eventType());
            }
        }
        if (envelope.aggregateId() != null && !envelope.aggregateId().isBlank()) {
            try {
                return UUID.fromString(envelope.aggregateId());
            } catch (IllegalArgumentException ex) {
                logger.warn("Invalid aggregateId '{}' for eventType={}", envelope.aggregateId(), envelope.eventType());
            }
        }
        return null;
    }

    private long resolveTipCents(EventEnvelope envelope) {
        if (envelope.payload() == null || envelope.payload().isNull()) {
            return 0L;
        }
        if (envelope.payload().hasNonNull("tipCents")) {
            return Math.max(0L, envelope.payload().get("tipCents").asLong(0L));
        }
        if (envelope.payload().hasNonNull("tip_cents")) {
            return Math.max(0L, envelope.payload().get("tip_cents").asLong(0L));
        }
        return 0L;
    }
}
