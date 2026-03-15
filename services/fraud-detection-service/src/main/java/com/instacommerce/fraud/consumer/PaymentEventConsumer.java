package com.instacommerce.fraud.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.contracts.events.EventEnvelope;
import com.instacommerce.contracts.topics.TopicNames;
import com.instacommerce.fraud.service.VelocityService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final VelocityService velocityService;

    public PaymentEventConsumer(ObjectMapper objectMapper, VelocityService velocityService) {
        this.objectMapper = objectMapper;
        this.velocityService = velocityService;
    }

    @KafkaListener(topics = TopicNames.PAYMENTS_EVENTS, groupId = "fraud-detection-payments")
    public void onPaymentEvent(ConsumerRecord<String, String> record) {
        try {
            EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
            if (!"PaymentFailed".equals(envelope.eventType())) {
                return;
            }

            JsonNode payload = envelope.payload();
            String userId = payload.path("userId").asText(null);

            if (userId == null) {
                log.warn("PaymentFailed event missing userId at offset={}", record.offset());
                return;
            }

            velocityService.incrementCounter("USER", userId, "FAILED_PAYMENTS_1H");

            String deviceFingerprint = payload.path("deviceFingerprint").asText(null);
            if (deviceFingerprint != null && !deviceFingerprint.isBlank()) {
                velocityService.incrementCounter("DEVICE", deviceFingerprint, "FAILED_PAYMENTS_1H");
            }

            String ipAddress = payload.path("ipAddress").asText(null);
            if (ipAddress != null && !ipAddress.isBlank()) {
                velocityService.incrementCounter("IP", ipAddress, "FAILED_PAYMENTS_1H");
            }

            log.debug("Processed PaymentFailed velocity update for user={}", userId);
        } catch (Exception ex) {
            log.error("Failed to process payment event: skipping record at offset={}, partition={}",
                    record.offset(), record.partition(), ex);
        }
    }
}
