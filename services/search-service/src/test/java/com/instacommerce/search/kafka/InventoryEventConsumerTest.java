package com.instacommerce.search.kafka;

import static org.mockito.Mockito.*;

import com.instacommerce.search.service.SearchIndexService;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InventoryEventConsumerTest {

    @Mock
    private SearchIndexService searchIndexService;

    private InventoryEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new InventoryEventConsumer(searchIndexService);
    }

    @Test
    void stockAdjustedToZero_setsOutOfStock() {
        UUID productId = UUID.randomUUID();
        Map<String, Object> event = new HashMap<>();
        event.put("eventType", "StockAdjusted");
        event.put("payload", Map.of("productId", productId.toString(), "newOnHand", 0));

        consumer.handleInventoryEvent(event);

        verify(searchIndexService).updateStockStatus(productId, false);
    }

    @Test
    void stockAdjustedPositive_setsInStock() {
        UUID productId = UUID.randomUUID();
        Map<String, Object> event = new HashMap<>();
        event.put("eventType", "StockAdjusted");
        event.put("payload", Map.of("productId", productId.toString(), "newOnHand", 25));

        consumer.handleInventoryEvent(event);

        verify(searchIndexService).updateStockStatus(productId, true);
    }

    @Test
    void lowStockAlertZero_setsOutOfStock() {
        UUID productId = UUID.randomUUID();
        Map<String, Object> event = new HashMap<>();
        event.put("eventType", "LowStockAlert");
        event.put("payload", Map.of("productId", productId.toString(), "currentQuantity", 0));

        consumer.handleInventoryEvent(event);

        verify(searchIndexService).updateStockStatus(productId, false);
    }

    @Test
    void flatFormatEvent_handledCorrectly() {
        UUID productId = UUID.randomUUID();
        Map<String, Object> event = new HashMap<>();
        event.put("eventType", "StockAdjusted");
        event.put("productId", productId.toString());
        event.put("newOnHand", 10);

        consumer.handleInventoryEvent(event);

        verify(searchIndexService).updateStockStatus(productId, true);
    }

    @Test
    void unknownEventType_ignored() {
        Map<String, Object> event = new HashMap<>();
        event.put("eventType", "WarehouseTransfer");

        consumer.handleInventoryEvent(event);

        verifyNoInteractions(searchIndexService);
    }

    @Test
    void nullEventType_ignored() {
        Map<String, Object> event = new HashMap<>();
        event.put("aggregateId", "something");

        consumer.handleInventoryEvent(event);

        verifyNoInteractions(searchIndexService);
    }

    @Test
    void stockConfirmed_itemsReachZero_setsOutOfStock() {
        UUID prod1 = UUID.randomUUID();
        UUID prod2 = UUID.randomUUID();

        Map<String, Object> item1 = Map.of(
            "productId", prod1.toString(),
            "quantity", 2,
            "remainingAvailable", 0,
            "inStock", false
        );
        Map<String, Object> item2 = Map.of(
            "productId", prod2.toString(),
            "quantity", 1,
            "remainingAvailable", 5,
            "inStock", true
        );

        Map<String, Object> payload = Map.of(
            "reservationId", UUID.randomUUID().toString(),
            "items", java.util.List.of(item1, item2)
        );

        Map<String, Object> event = Map.of(
            "eventType", "StockConfirmed",
            "payload", payload
        );

        consumer.handleInventoryEvent(event);

        verify(searchIndexService).updateStockStatus(prod1, false);
        verify(searchIndexService).updateStockStatus(prod2, true);
    }

    @Test
    void stockReleased_marksItemsInStock() {
        UUID prod1 = UUID.randomUUID();
        UUID prod2 = UUID.randomUUID();

        Map<String, Object> item1 = Map.of("productId", prod1.toString(), "quantity", 3);
        Map<String, Object> item2 = Map.of("productId", prod2.toString(), "quantity", 1);

        Map<String, Object> payload = Map.of(
            "reservationId", UUID.randomUUID().toString(),
            "reason", "CANCELLED",
            "items", java.util.List.of(item1, item2)
        );

        Map<String, Object> event = Map.of(
            "eventType", "StockReleased",
            "payload", payload
        );

        consumer.handleInventoryEvent(event);

        verify(searchIndexService).updateStockStatus(prod1, true);
        verify(searchIndexService).updateStockStatus(prod2, true);
    }
}
