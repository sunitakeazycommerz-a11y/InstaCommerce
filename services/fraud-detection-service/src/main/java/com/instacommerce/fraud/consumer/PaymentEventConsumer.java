package com.instacommerce.fraud.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.contracts.events.EventEnvelope;
import com.instacommerce.contracts.topics.TopicNames;
import com.instacommerce.fraud.service.BlocklistService;
import com.instacommerce.fraud.service.VelocityService;
import jakarta.annotation.Nullable;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    public static final String COUNTER_FAILED_PAYMENTS_5M = "FAILED_PAYMENTS_5M";
    public static final String COUNTER_FAILED_PAYMENTS_1H = "FAILED_PAYMENTS_1H";
    public static final String COUNTER_CHARGEBACKS_30D = "CHARGEBACKS_30D";
    public static final String COUNTER_REFUND_FAILURES_24H = "REFUND_FAILURES_24H";
    public static final int DEFAULT_CHARGEBACK_BLOCK_THRESHOLD = 2;

    private final ObjectMapper objectMapper;
    private final VelocityService velocityService;
    private final BlocklistService blocklistService;
    private final int chargebackBlockThreshold;

    public PaymentEventConsumer(ObjectMapper objectMapper, VelocityService velocityService) {
        this(objectMapper, velocityService, null, DEFAULT_CHARGEBACK_BLOCK_THRESHOLD);
    }

    public PaymentEventConsumer(
            ObjectMapper objectMapper,
            VelocityService velocityService,
            @Nullable BlocklistService blocklistService) {
        this(objectMapper, velocityService, blocklistService, DEFAULT_CHARGEBACK_BLOCK_THRESHOLD);
    }

    @Autowired
    public PaymentEventConsumer(
            ObjectMapper objectMapper,
            VelocityService velocityService,
            @Nullable BlocklistService blocklistService,
            @Value("${fraud.chargeback.auto-block-threshold:2}") int chargebackBlockThreshold) {
        this.objectMapper = objectMapper;
        this.velocityService = velocityService;
        this.blocklistService = blocklistService;
        this.chargebackBlockThreshold = Math.max(1, chargebackBlockThreshold);
    }

    @KafkaListener(topics = TopicNames.PAYMENTS_EVENTS, groupId = "fraud-detection-payments")
    public void onPaymentEvent(ConsumerRecord<String, String> record) {
        EventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Failed to deserialize payment event at offset=" + record.offset(), ex);
        }
        if (envelope == null || envelope.eventType() == null) {
            return;
        }
        switch (envelope.eventType()) {
            case "PaymentFailed" -> handlePaymentFailed(envelope.payload(), record.offset());
            case "PaymentDisputed" -> handlePaymentDisputed(envelope.payload(), record.offset());
            case "PaymentRefundFailed" -> handlePaymentRefundFailed(envelope.payload(), record.offset());
            default -> log.debug("Ignoring payment event type={} at offset={}",
                    envelope.eventType(), record.offset());
        }
    }

    private void handlePaymentFailed(JsonNode payload, long offset) {
        String userId = extractText(payload, "userId");
        if (userId == null) {
            log.warn("PaymentFailed event missing userId at offset={}", offset);
            return;
        }
        incrementEntityCounters(payload, userId, COUNTER_FAILED_PAYMENTS_5M);
        incrementEntityCounters(payload, userId, COUNTER_FAILED_PAYMENTS_1H);
        log.debug("Processed PaymentFailed velocity update for user={}", userId);
    }

    private void handlePaymentDisputed(JsonNode payload, long offset) {
        String userId = extractText(payload, "userId");
        if (userId == null) {
            log.warn("PaymentDisputed event missing userId at offset={}", offset);
            return;
        }
        incrementEntityCounters(payload, userId, COUNTER_CHARGEBACKS_30D);
        long chargebackCount = velocityService.getCount("USER", userId, COUNTER_CHARGEBACKS_30D);
        if (blocklistService != null && chargebackCount >= chargebackBlockThreshold) {
            if (!blocklistService.isBlocked("USER", userId)) {
                String disputeReason = extractText(payload, "reason");
                String blockReason = disputeReason != null
                        ? "Auto-blocked: chargeback threshold breached (" + disputeReason + ")"
                        : "Auto-blocked: chargeback threshold breached";
                blocklistService.block("USER", userId, blockReason, null, "system:payment-disputed");
                log.info("Auto-blocklisted user={} after chargebacks={}", userId, chargebackCount);
            } else {
                log.debug("User={} already blocklisted for chargeback threshold breach", userId);
            }
        }
        log.debug("Processed PaymentDisputed velocity update for user={}", userId);
    }

    private void handlePaymentRefundFailed(JsonNode payload, long offset) {
        String userId = extractText(payload, "userId");
        if (userId == null) {
            log.warn("PaymentRefundFailed event missing userId at offset={}", offset);
            return;
        }
        incrementEntityCounters(payload, userId, COUNTER_REFUND_FAILURES_24H);
        log.debug("Processed PaymentRefundFailed velocity update for user={}", userId);
    }

    private void incrementEntityCounters(JsonNode payload, String userId, String counterType) {
        velocityService.incrementCounter("USER", userId, counterType);
        String deviceFingerprint = extractText(payload, "deviceFingerprint");
        if (deviceFingerprint != null) {
            velocityService.incrementCounter("DEVICE", deviceFingerprint, counterType);
        }
        String ipAddress = extractText(payload, "ipAddress");
        if (ipAddress != null) {
            velocityService.incrementCounter("IP", ipAddress, counterType);
        }
    }

    private static String extractText(JsonNode payload, String fieldName) {
        if (payload == null || payload.isNull()) {
            return null;
        }
        String value = payload.path(fieldName).asText(null);
        return (value != null && !value.isBlank()) ? value.trim() : null;
    }
}
