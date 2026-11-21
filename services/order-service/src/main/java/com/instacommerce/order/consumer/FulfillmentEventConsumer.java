package com.instacommerce.order.consumer;

import com.fasterxml.jackson.databind.JsonNode;
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
            case "DeliveryFailed", "FulfillmentFailed" -> OrderStatus.FAILED;
            case "OrderModified" -> {
                FulfillmentOrderEvent event = (envelope.payload() != null && !envelope.payload().isNull())
                    ? objectMapper.treeToValue(envelope.payload(), FulfillmentOrderEvent.class)
                    : null;
                UUID orderId = (event != null && event.orderId() != null)
                    ? event.orderId()
                    : resolveOrderId(envelope);
                if (orderId != null && event != null
                        && (event.productId() != null || (event.itemDeltas() != null && !event.itemDeltas().isEmpty()))) {
                    orderService.applyOrderModification(new FulfillmentOrderEvent(
                        orderId,
                        event.productId(),
                        event.missingQty(),
                        event.refundCents(),
                        event.newVersion(),
                        event.priceDeltaCents(),
                        event.itemDeltas()
                    ));
                } else {
                    logger.info("Ignoring OrderModified event missing fields for aggregateId={}", envelope.aggregateId());
                }
                yield null;
            }
            case "ProofOfDeliveryUploaded" -> {
                UUID orderId = resolveOrderId(envelope);
                String photoUrl = extractPayloadText(envelope.payload(), "photoUrl", "podUrl");
                String format = extractPayloadText(envelope.payload(), "format");
                orderService.recordFulfillmentAuditNote(orderId, CHANGED_BY, buildPodAuditNote(photoUrl, format));
                yield null;
            }
            case "ItemSubstituted" -> {
                UUID orderId = resolveOrderId(envelope);
                String originalProductId = extractPayloadText(envelope.payload(), "originalProductId");
                String substituteProductId = extractPayloadText(envelope.payload(), "substituteProductId");
                int quantity = (envelope.payload() != null && envelope.payload().hasNonNull("substitutedQuantity"))
                    ? envelope.payload().get("substitutedQuantity").asInt(0)
                    : 0;
                orderService.recordFulfillmentAuditNote(
                    orderId,
                    CHANGED_BY,
                    buildSubstitutionAuditNote(originalProductId, substituteProductId, quantity));
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
            case FAILED -> "delivery-failed";
            default -> "fulfillment-event";
        };
        orderService.advanceLifecycleFromFulfillment(orderId, targetStatus, CHANGED_BY, note);
    }

    private static String extractPayloadText(JsonNode payload, String... fieldNames) {
        if (payload == null || payload.isNull()) {
            return null;
        }
        for (String fieldName : fieldNames) {
            String value = payload.path(fieldName).asText(null);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String buildPodAuditNote(String photoUrl, String format) {
        if (photoUrl == null) {
            return "pod-uploaded";
        }
        return format != null
            ? "pod-uploaded:" + photoUrl + " (" + format + ")"
            : "pod-uploaded:" + photoUrl;
    }

    private static String buildSubstitutionAuditNote(String originalProductId,
                                                     String substituteProductId,
                                                     int quantity) {
        if (originalProductId != null && substituteProductId != null) {
            return quantity > 0
                ? "item-substituted:" + originalProductId + "->" + substituteProductId + ":qty=" + quantity
                : "item-substituted:" + originalProductId + "->" + substituteProductId;
        }
        return "item-substituted";
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
