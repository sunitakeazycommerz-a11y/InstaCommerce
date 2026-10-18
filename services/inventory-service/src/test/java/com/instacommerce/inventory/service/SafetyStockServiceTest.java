package com.instacommerce.inventory.service;

import com.instacommerce.inventory.domain.model.SkuSafetyStockBuffer;
import com.instacommerce.inventory.domain.model.StockItem;
import com.instacommerce.inventory.dto.request.SafetyStockItemRequest;
import com.instacommerce.inventory.dto.response.SafetyStockResponse;
import com.instacommerce.inventory.repository.SkuSafetyStockBufferRepository;
import com.instacommerce.inventory.repository.StockItemRepository;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SafetyStockServiceTest {

    @Mock
    private SkuSafetyStockBufferRepository bufferRepository;

    @Mock
    private StockItemRepository stockItemRepository;

    @Mock
    private OutboxService outboxService;

    private SafetyStockService safetyStockService;

    @BeforeEach
    void setUp() {
        safetyStockService = new SafetyStockService(bufferRepository, stockItemRepository, outboxService);
    }

    @Test
    @DisplayName("Calculate dynamic safety stock and trigger reorder event when stock hits threshold")
    void shouldCalculateDynamicSafetyStockAndTriggerReorder() {
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        // 95% service level (z=1.65), daily std dev demand = 10, lead time = 4 days
        // safety_stock = ceil(1.65 * 10 * sqrt(4)) = ceil(33.0) = 33
        // reorder_threshold = 33 + ceil(10 * 4) = 73
        SafetyStockItemRequest request = new SafetyStockItemRequest(
            productId,
            new BigDecimal("1.65"),
            new BigDecimal("10.00"),
            4
        );

        when(bufferRepository.findByStoreIdAndProductId(storeId, productId))
            .thenReturn(Optional.empty());
        when(bufferRepository.save(any(SkuSafetyStockBuffer.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // Mock current available stock is 30 units (below reorder threshold 73)
        StockItem stockItem = new StockItem();
        stockItem.setStoreId(storeId);
        stockItem.setProductId(productId);
        stockItem.setOnHand(40);
        stockItem.setReserved(10); // available = 30
        when(stockItemRepository.findByStoreIdAndProductId(storeId, productId))
            .thenReturn(Optional.of(stockItem));

        SafetyStockResponse response = safetyStockService.ingestBuffer(storeId, request);

        assertNotNull(response);
        assertEquals(33, response.calculatedSafetyStock());
        assertEquals(73, response.reorderThreshold());
        assertEquals(30, response.currentAvailable());
        assertTrue(response.reorderNeeded());

        // Verify outbox event emitted
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(outboxService).publish(
            eq("INVENTORY_SAFETY_STOCK"),
            eq(storeId + ":" + productId),
            eq("ReorderPointReached"),
            payloadCaptor.capture()
        );

        Map<String, Object> payload = payloadCaptor.getValue();
        assertEquals(storeId.toString(), payload.get("store_id"));
        assertEquals(productId.toString(), payload.get("product_id"));
        assertEquals(30, payload.get("available_stock"));
        assertEquals(33, payload.get("safety_stock_buffer"));
        assertEquals(73, payload.get("reorder_threshold"));
    }
}
