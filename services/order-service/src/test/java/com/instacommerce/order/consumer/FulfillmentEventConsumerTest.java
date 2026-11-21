package com.instacommerce.order.consumer;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.order.domain.model.OrderStatus;
import com.instacommerce.order.service.OrderService;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FulfillmentEventConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private OrderService orderService;

    @Test
    void mapsDispatchedEventToOutForDelivery() throws Exception {
        UUID orderId = UUID.randomUUID();
        FulfillmentEventConsumer consumer = new FulfillmentEventConsumer(objectMapper, orderService);

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
            "fulfillment.events",
            0,
            0L,
            orderId.toString(),
            """
                {"eventType":"OrderDispatched","aggregateId":"%s","payload":{"orderId":"%s"}}
                """.formatted(orderId, orderId));

        consumer.onFulfillmentEvent(record);

        verify(orderService).advanceLifecycleFromFulfillment(
            orderId,
            OrderStatus.OUT_FOR_DELIVERY,
            "system:fulfillment-event",
            "order-dispatched");
    }

    @Test
    void mapsDeliveryFailedEventToFailed() throws Exception {
        UUID orderId = UUID.randomUUID();
        FulfillmentEventConsumer consumer = new FulfillmentEventConsumer(objectMapper, orderService);

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
            "fulfillment.events",
            0,
            0L,
            orderId.toString(),
            """
                {"eventType":"DeliveryFailed","aggregateId":"%s","payload":{"orderId":"%s"}}
                """.formatted(orderId, orderId));

        consumer.onFulfillmentEvent(record);

        verify(orderService).advanceLifecycleFromFulfillment(
            orderId,
            OrderStatus.FAILED,
            "system:fulfillment-event",
            "delivery-failed");
    }

    @Test
    void ignoresUnsupportedFulfillmentEvent() throws Exception {
        UUID orderId = UUID.randomUUID();
        FulfillmentEventConsumer consumer = new FulfillmentEventConsumer(objectMapper, orderService);

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
            "fulfillment.events",
            0,
            0L,
            orderId.toString(),
            """
                {"eventType":"OrderModified","aggregateId":"%s","payload":{"orderId":"%s"}}
                """.formatted(orderId, orderId));

        consumer.onFulfillmentEvent(record);

        verifyNoInteractions(orderService);
    }

    @Test
    void routesOrderModifiedWithProductOrItemDeltasToOrderService() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        FulfillmentEventConsumer consumer = new FulfillmentEventConsumer(objectMapper, orderService);

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
            "fulfillment.events",
            0,
            0L,
            orderId.toString(),
            """
                {"eventType":"OrderModified","aggregateId":"%s","payload":{"orderId":"%s","productId":"%s","missingQty":1,"refundCents":500,"newVersion":2,"priceDeltaCents":-500}}
                """.formatted(orderId, orderId, productId));

        consumer.onFulfillmentEvent(record);

        verify(orderService).applyOrderModification(new FulfillmentOrderEvent(
            orderId, productId, 1, 500L, 2L, -500L, null));
    }

    @Test
    void routesProofOfDeliveryUploadedToAuditNoteWithoutChangingStatus() throws Exception {
        UUID orderId = UUID.randomUUID();
        FulfillmentEventConsumer consumer = new FulfillmentEventConsumer(objectMapper, orderService);

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
            "fulfillment.events",
            0,
            0L,
            orderId.toString(),
            """
                {"eventType":"ProofOfDeliveryUploaded","aggregateId":"%s","payload":{"orderId":"%s","photoUrl":"https://cdn.instacommerce.dev/pod/123.webp","lifecycleDays":30,"format":"WEBP"}}
                """.formatted(orderId, orderId));

        consumer.onFulfillmentEvent(record);

        verify(orderService).recordFulfillmentAuditNote(
            orderId,
            "system:fulfillment-event",
            "pod-uploaded:https://cdn.instacommerce.dev/pod/123.webp (WEBP)");
    }

    @Test
    void routesItemSubstitutedToAuditNoteWithoutChangingStatus() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID originalProductId = UUID.randomUUID();
        UUID substituteProductId = UUID.randomUUID();
        FulfillmentEventConsumer consumer = new FulfillmentEventConsumer(objectMapper, orderService);

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
            "fulfillment.events",
            0,
            0L,
            orderId.toString(),
            """
                {"eventType":"ItemSubstituted","aggregateId":"%s","payload":{"orderId":"%s","storeId":"%s","originalProductId":"%s","substituteProductId":"%s","substitutedQuantity":2}}
                """.formatted(orderId, orderId, UUID.randomUUID(), originalProductId, substituteProductId));

        consumer.onFulfillmentEvent(record);

        verify(orderService).recordFulfillmentAuditNote(
            orderId,
            "system:fulfillment-event",
            "item-substituted:" + originalProductId + "->" + substituteProductId + ":qty=2");
    }
}
