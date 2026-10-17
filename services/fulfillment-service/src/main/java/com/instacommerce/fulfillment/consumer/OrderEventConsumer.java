package com.instacommerce.fulfillment.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.contracts.topics.TopicNames;
import com.instacommerce.fulfillment.service.PickService;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderEventConsumer {
    private static final Logger logger = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final PickService pickService;
    private final ObjectMapper objectMapper;

    public OrderEventConsumer(PickService pickService, ObjectMapper objectMapper) {
        this.pickService = pickService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = TopicNames.ORDERS_EVENTS, groupId = "fulfillment-service")
    public void onOrderEvent(ConsumerRecord<String, String> record) throws Exception {
        OrderEvent event = objectMapper.readValue(record.value(), OrderEvent.class);
        if ("OrderPlaced".equals(event.eventType())) {
            OrderPlacedPayload payload = objectMapper.treeToValue(event.payload(), OrderPlacedPayload.class);
            pickService.createPickTask(payload);
            logger.info("Pick task created for order event key={}", record.key());
        } else if ("OrderCancelled".equals(event.eventType())) {
            String orderIdStr = null;
            if (event.payload() != null) {
                if (event.payload().hasNonNull("orderId")) {
                    orderIdStr = event.payload().get("orderId").asText();
                } else if (event.payload().hasNonNull("order_id")) {
                    orderIdStr = event.payload().get("order_id").asText();
                }
            }
            if ((orderIdStr == null || orderIdStr.isBlank()) && record.key() != null && !record.key().isBlank()) {
                orderIdStr = record.key();
            }
            if (orderIdStr != null && !orderIdStr.isBlank()) {
                try {
                    UUID orderId = UUID.fromString(orderIdStr);
                    String reason = (event.payload() != null && event.payload().hasNonNull("reason"))
                            ? event.payload().get("reason").asText() : "order-cancelled";
                    pickService.cancelPickTask(orderId, reason);
                    logger.info("Pick task cancelled for orderId={} key={}", orderId, record.key());
                } catch (IllegalArgumentException ex) {
                    logger.warn("Invalid orderId format in OrderCancelled event: {}", orderIdStr);
                }
            }
        }
    }
}
