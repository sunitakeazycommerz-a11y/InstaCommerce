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
}
