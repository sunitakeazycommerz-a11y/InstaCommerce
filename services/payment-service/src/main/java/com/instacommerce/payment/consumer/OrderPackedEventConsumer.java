package com.instacommerce.payment.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.contracts.topics.TopicNames;
import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.repository.PaymentRepository;
import com.instacommerce.payment.service.PaymentService;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code OrderPacked} domain events from {@code fulfillment.events} and triggers
 * payment capture only after physical warehouse packing verification (Issue #531).
 */
@Component
@ConditionalOnProperty(
    prefix = "payment.choreography",
    name = "order-packed-consumer-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OrderPackedEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderPackedEventConsumer.class);
    private static final String EVENT_TYPE = "OrderPacked";

    private final ObjectMapper objectMapper;
    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;

    public OrderPackedEventConsumer(
            ObjectMapper objectMapper,
            PaymentRepository paymentRepository,
            PaymentService paymentService) {
        this.objectMapper = objectMapper;
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
    }

    @KafkaListener(
        topics = "${payment.choreography.order-packed-topic:" + TopicNames.FULFILLMENT_EVENTS + "}",
        groupId = "${payment.choreography.order-packed-consumer-group:payment-service-order-packed}",
        properties = "auto.offset.reset=earliest")
    @Transactional
    public void onFulfillmentEvent(ConsumerRecord<String, String> record) throws Exception {
        if (record.value() == null || record.value().isBlank()) {
            return;
        }

        JsonNode root = objectMapper.readTree(record.value());
        String eventType = resolveEventType(root);
        if (!EVENT_TYPE.equals(eventType)) {
            return;
        }

        UUID orderId = resolveOrderId(root);
        if (orderId == null) {
            log.warn("OrderPacked event missing valid orderId in record offset={}", record.offset());
            return;
        }

        Long fulfilledAmountCents = resolveFulfilledAmount(root);
        log.info("Processing OrderPacked event for orderId={}, fulfilledAmountCents={}", orderId, fulfilledAmountCents);

        Optional<Payment> paymentOpt = paymentRepository.findByOrderIdForUpdate(orderId);
        if (paymentOpt.isEmpty()) {
            log.warn("No payment found for packed orderId={}", orderId);
            return;
        }

        Payment payment = paymentOpt.get();
        PaymentStatus currentStatus = payment.getStatus();

        if (currentStatus == PaymentStatus.CAPTURED || currentStatus == PaymentStatus.CAPTURE_PENDING) {
            log.info("Payment {} for orderId={} is already in state {}, skipping duplicate capture",
                payment.getId(), orderId, currentStatus);
            return;
        }

        if (currentStatus != PaymentStatus.AUTHORIZED) {
            log.warn("Payment {} for orderId={} is in non-capturable state {}, skipping capture",
                payment.getId(), orderId, currentStatus);
            return;
        }

        String captureIdempotencyKey = "packed-capture-" + orderId + "-" + payment.getId();
        paymentService.capture(payment.getId(), fulfilledAmountCents, captureIdempotencyKey);
        log.info("Successfully triggered payment capture for payment={} on orderId={}", payment.getId(), orderId);
    }

    private String resolveEventType(JsonNode root) {
        if (root.hasNonNull("eventType")) {
            return root.get("eventType").asText();
        }
        if (root.hasNonNull("event_type")) {
            return root.get("event_type").asText();
        }
        return null;
    }

    private UUID resolveOrderId(JsonNode root) {
        JsonNode payload = root.has("payload") ? root.get("payload") : root;
        String rawOrderId = null;
        if (payload.hasNonNull("orderId")) {
            rawOrderId = payload.get("orderId").asText();
        } else if (payload.hasNonNull("order_id")) {
            rawOrderId = payload.get("order_id").asText();
        } else if (root.hasNonNull("aggregateId")) {
            rawOrderId = root.get("aggregateId").asText();
        } else if (root.hasNonNull("aggregate_id")) {
            rawOrderId = root.get("aggregate_id").asText();
        }

        if (rawOrderId == null || rawOrderId.isBlank()) {
            return null;
        }

        try {
            return UUID.fromString(rawOrderId);
        } catch (IllegalArgumentException ex) {
            log.warn("Malformed UUID orderId: {}", rawOrderId);
            return null;
        }
    }

    private Long resolveFulfilledAmount(JsonNode root) {
        JsonNode payload = root.has("payload") ? root.get("payload") : root;
        if (payload.hasNonNull("fulfilledAmountCents")) {
            return payload.get("fulfilledAmountCents").asLong();
        }
        if (payload.hasNonNull("fulfilled_amount_cents")) {
            return payload.get("fulfilled_amount_cents").asLong();
        }
        if (payload.hasNonNull("totalAmountCents")) {
            return payload.get("totalAmountCents").asLong();
        }
        if (payload.hasNonNull("total_amount_cents")) {
            return payload.get("total_amount_cents").asLong();
        }
        return null;
    }
}
