package com.instacommerce.fraud.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.fraud.service.VelocityService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final VelocityService velocityService;

    public OrderEventConsumer(ObjectMapper objectMapper, VelocityService velocityService) {
        this.objectMapper = objectMapper;
        this.velocityService = velocityService;
    }

    @KafkaListener(topics = "order.events", groupId = "fraud-detection-orders")
    public void onOrderEvent(ConsumerRecord<String, String> record) {
        try {
            EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
            if (!"OrderPlaced".equals(envelope.eventType())) {
                return;
            }

            JsonNode payload = envelope.payload();
            String userId = payload.path("userId").asText(null);
            String orderId = payload.path("orderId").asText(null);
            long totalCents = payload.path("totalCents").asLong(0);

            if (userId == null) {
                log.warn("OrderPlaced event missing userId at offset={}", record.offset());
                return;
            }

            velocityService.incrementCounter("USER", userId, "ORDERS_1H");
            velocityService.incrementCounter("USER", userId, "ORDERS_24H");

            if (totalCents > 0) {
                velocityService.incrementAmountCounter("USER", userId, "AMOUNT_24H", totalCents);
            }

            String deviceFingerprint = payload.path("deviceFingerprint").asText(null);
            if (deviceFingerprint != null && !deviceFingerprint.isBlank()) {
                velocityService.incrementCounter("DEVICE", deviceFingerprint, "ORDERS_1H");
                velocityService.incrementCounter("DEVICE", deviceFingerprint, "ORDERS_24H");
            }

            String ipAddress = payload.path("ipAddress").asText(null);
            if (ipAddress != null && !ipAddress.isBlank()) {
                velocityService.incrementCounter("IP", ipAddress, "ORDERS_1H");
                velocityService.incrementCounter("IP", ipAddress, "ORDERS_24H");
            }

            log.debug("Processed OrderPlaced velocity update for user={}, order={}", userId, orderId);
        } catch (Exception ex) {
            log.error("Failed to process order event: skipping record at offset={}, partition={}",
                    record.offset(), record.partition(), ex);
        }
    }
}
