package com.instacommerce.order.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.contracts.events.EventEnvelope;
import com.instacommerce.order.domain.model.OrderStatus;
import com.instacommerce.order.service.OrderService;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    prefix = "order.choreography",
    name = "fulfillment-consumer-enabled",
    havingValue = "true")
public class FulfillmentEventConsumer {
    private static final Logger logger = LoggerFactory.getLogger(FulfillmentEventConsumer.class);
    private static final String CHANGED_BY = "system:fulfillment-event";

    private final ObjectMapper objectMapper;
    private final OrderService orderService;

    public FulfillmentEventConsumer(ObjectMapper objectMapper, OrderService orderService) {
        this.objectMapper = objectMapper;
        this.orderService = orderService;
    }

    @KafkaListener(
        topics = "${order.choreography.fulfillment-topic:fulfillment.events}",
        groupId = "${order.choreography.fulfillment-consumer-group:order-service-fulfillment}",
        properties = "auto.offset.reset=earliest")
    public void onFulfillmentEvent(ConsumerRecord<String, String> record) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        OrderStatus targetStatus = switch (envelope.eventType()) {
            case "OrderPacked" -> OrderStatus.PACKED;
            case "OrderDispatched" -> OrderStatus.OUT_FOR_DELIVERY;
            case "OrderDelivered" -> OrderStatus.DELIVERED;
            case "OrderModified" -> {
                logger.info("Ignoring OrderModified event for aggregateId={}", envelope.aggregateId());
                yield null;
            }
            default -> {
                logger.debug("Ignoring unsupported fulfillment event type={}", envelope.eventType());
                yield null;
            }
        };
        if (targetStatus == null) {
            return;
        }

        UUID orderId = resolveOrderId(envelope);
        String note = switch (targetStatus) {
            case PACKED -> "order-packed";
            case OUT_FOR_DELIVERY -> "order-dispatched";
            case DELIVERED -> "order-delivered";
            default -> "fulfillment-event";
        };
        orderService.advanceLifecycleFromFulfillment(orderId, targetStatus, CHANGED_BY, note);
    }

    private UUID resolveOrderId(EventEnvelope envelope) throws Exception {
        if (envelope.payload() != null && !envelope.payload().isNull()) {
            FulfillmentOrderEvent event = objectMapper.treeToValue(envelope.payload(), FulfillmentOrderEvent.class);
            if (event.orderId() != null) {
                return event.orderId();
            }
        }
        if (envelope.aggregateId() != null && !envelope.aggregateId().isBlank()) {
            return UUID.fromString(envelope.aggregateId());
        }
        throw new IllegalArgumentException("Fulfillment event missing orderId");
    }
}
