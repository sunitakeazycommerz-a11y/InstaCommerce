package com.instacommerce.inventory.service;

import com.instacommerce.inventory.domain.model.StockBatch;
import com.instacommerce.inventory.dto.request.CreateBatchRequest;
import com.instacommerce.inventory.dto.response.StockBatchResponse;
import com.instacommerce.inventory.repository.StockBatchRepository;
import com.instacommerce.inventory.repository.StockItemRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PerishableLiquidationServiceTest {

    @Mock
    private StockBatchRepository batchRepository;

    @Mock
    private StockItemRepository stockItemRepository;

    @Mock
    private OutboxService outboxService;

    private PerishableLiquidationService liquidationService;

    @BeforeEach
    void setUp() {
        liquidationService = new PerishableLiquidationService(batchRepository, stockItemRepository, outboxService);
    }

    @Test
    @DisplayName("Create or update perishable stock batch")
    void shouldCreateOrUpdateBatch() {
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Instant expiry = Instant.now().plus(Duration.ofDays(3));
        CreateBatchRequest request = new CreateBatchRequest(productId, "BATCH-101", 50, expiry);

        when(batchRepository.findByStoreIdAndProductIdAndBatchNumber(storeId, productId, "BATCH-101"))
            .thenReturn(Optional.empty());
        when(batchRepository.save(any(StockBatch.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(stockItemRepository.findByStoreIdAndProductId(storeId, productId))
            .thenReturn(Optional.empty());

        StockBatchResponse response = liquidationService.createOrUpdateBatch(storeId, request);

        assertNotNull(response);
        assertEquals("BATCH-101", response.batchNumber());
        assertEquals(50, response.quantity());
        assertFalse(response.isDiscounted());
    }

    @Test
    @DisplayName("Trigger 50% markdown on perishable batches expiring within 24 hours and emit outbox event")
    void shouldTriggerMarkdownOnExpiringBatches() {
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();

        StockBatch expiringBatch = new StockBatch();
        expiringBatch.setId(batchId);
        expiringBatch.setStoreId(storeId);
        expiringBatch.setProductId(productId);
        expiringBatch.setBatchNumber("MILK-001");
        expiringBatch.setQuantity(20);
        expiringBatch.setExpiryDate(Instant.now().plus(Duration.ofHours(12)));
        expiringBatch.setDiscounted(false);

        when(batchRepository.findByStoreIdAndExpiryDateLessThanEqualAndIsDiscountedFalseAndQuantityGreaterThan(
            eq(storeId), any(Instant.class), anyInt()))
            .thenReturn(List.of(expiringBatch));
        when(batchRepository.save(any(StockBatch.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        List<StockBatchResponse> result = liquidationService.triggerMarkdownsForStore(storeId, Duration.ofHours(24));

        assertEquals(1, result.size());
        StockBatchResponse response = result.get(0);
        assertTrue(response.isDiscounted());
        assertEquals(50, response.discountPercent());

        // Verify outbox event emitted for pricing-service
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(outboxService).publish(
            eq("STOCK_BATCH"),
            eq(batchId.toString()),
            eq("PerishableExpiringSoon"),
            payloadCaptor.capture()
        );

        Map<String, Object> payload = payloadCaptor.getValue();
        assertEquals(storeId.toString(), payload.get("store_id"));
        assertEquals(productId.toString(), payload.get("product_id"));
        assertEquals("MILK-001", payload.get("batch_number"));
        assertEquals(20, payload.get("quantity"));
        assertEquals(50, payload.get("discount_percent"));
        assertEquals("Save Food / Quick Sale", payload.get("featured_section"));
    }
}
