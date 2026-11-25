package com.instacommerce.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.inventory.config.InventoryProperties;
import com.instacommerce.inventory.domain.model.SkuSafetyStockBuffer;
import com.instacommerce.inventory.domain.model.StockItem;
import com.instacommerce.inventory.repository.SkuSafetyStockBufferRepository;
import com.instacommerce.inventory.repository.StockItemRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StockLevelMonitorTest {

    @Mock
    private SkuSafetyStockBufferRepository bufferRepository;

    @Mock
    private StockItemRepository stockItemRepository;

    @Mock
    private OutboxService outboxService;

    @Captor
    private ArgumentCaptor<Map<String, Object>> lowStockCaptor;

    @Test
    @DisplayName("Emits LowStockAlert using SkuSafetyStockBuffer threshold and triggers ReorderPointReached")
    void usesSafetyStockBufferThresholdAndTriggersReorder() {
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        SkuSafetyStockBuffer buffer = new SkuSafetyStockBuffer();
        buffer.setStoreId(storeId);
        buffer.setProductId(productId);
        buffer.setZScore(new BigDecimal("1.65"));
        buffer.setStdDevDemand(new BigDecimal("4.00"));
        buffer.setLeadTimeDays(4);
        buffer.recalculate();

        StockItem stock = new StockItem();
        stock.setStoreId(storeId);
        stock.setProductId(productId);
        stock.setOnHand(buffer.getReorderThreshold());
        stock.setReserved(0);

        when(bufferRepository.findByStoreIdAndProductId(storeId, productId)).thenReturn(Optional.of(buffer));
        when(bufferRepository.save(any(SkuSafetyStockBuffer.class))).thenAnswer(inv -> inv.getArgument(0));

        SafetyStockService safetyStockService = new SafetyStockService(bufferRepository, stockItemRepository, outboxService);
        InventoryProperties props = new InventoryProperties();
        props.setLowStockThreshold(2);
        StockLevelMonitor monitor = new StockLevelMonitor(props, outboxService, bufferRepository, safetyStockService);

        monitor.evaluateAfterStockDecrease(stock, storeId);

        verify(outboxService).publish(eq("StockItem"), eq(productId.toString()), eq("LowStockAlert"), lowStockCaptor.capture());
        assertThat(lowStockCaptor.getValue()).containsEntry("threshold", buffer.getReorderThreshold());
        verify(outboxService).publish(
            eq("INVENTORY_SAFETY_STOCK"),
            eq(storeId + ":" + productId),
            eq("ReorderPointReached"),
            any()
        );
        verify(outboxService, never()).publish(eq("StockItem"), any(), eq("OutOfStock"), any());
    }

    @Test
    @DisplayName("Emits OutOfStock and LowStockAlert when available hits zero and respects reorder cooldown")
    void emitsOutOfStockAndRespectsReorderCooldown() {
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        SkuSafetyStockBuffer buffer = new SkuSafetyStockBuffer();
        buffer.setStoreId(storeId);
        buffer.setProductId(productId);
        buffer.setReorderThreshold(10);
        buffer.setCalculatedSafetyStock(5);
        buffer.setLeadTimeDays(2);
        buffer.setLastReorderEventAt(Instant.now().minus(Duration.ofMinutes(30)));

        StockItem stock = new StockItem();
        stock.setStoreId(storeId);
        stock.setProductId(productId);
        stock.setOnHand(5);
        stock.setReserved(5);

        when(bufferRepository.findByStoreIdAndProductId(storeId, productId)).thenReturn(Optional.of(buffer));
        SafetyStockService safetyStockService = new SafetyStockService(bufferRepository, stockItemRepository, outboxService);
        StockLevelMonitor monitor = new StockLevelMonitor(new InventoryProperties(), outboxService, bufferRepository, safetyStockService);

        monitor.evaluateAfterStockDecrease(stock, storeId);

        verify(outboxService).publish(eq("StockItem"), eq(productId.toString()), eq("OutOfStock"), any());
        verify(outboxService).publish(eq("StockItem"), eq(productId.toString()), eq("LowStockAlert"), any());
        verify(outboxService, never()).publish(eq("INVENTORY_SAFETY_STOCK"), any(), eq("ReorderPointReached"), any());
    }

    @Test
    @DisplayName("Uses FEFO sellable available quantity and deduplicates repeated alerts within cooldown")
    void usesSellableAvailableAndDeduplicatesAlertsWithinCooldown() {
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        StockItem stock = new StockItem();
        stock.setStoreId(storeId);
        stock.setProductId(productId);
        stock.setOnHand(20);
        stock.setReserved(0);

        FefoBatchAllocationService fefoService = mock(FefoBatchAllocationService.class);
        when(fefoService.resolveSellableAvailable(stock))
            .thenReturn(0, 0, 25, 0);

        Clock fixedClock = Clock.fixed(Instant.parse("2026-03-20T12:00:00Z"), ZoneOffset.UTC);
        InventoryProperties props = new InventoryProperties();
        props.setLowStockThreshold(10);
        props.setAlertCooldownMinutes(15);

        StockLevelMonitor monitor = new StockLevelMonitor(
            props, outboxService, null, null, fefoService, fixedClock);

        // First drop to sellable=0 emits alerts
        monitor.evaluateAfterStockDecrease(stock, storeId);
        // Repeated drop within cooldown does not emit duplicate alerts
        monitor.evaluateAfterStockDecrease(stock, storeId);
        verify(outboxService, times(1)).publish(eq("StockItem"), eq(productId.toString()), eq("OutOfStock"), any());
        verify(outboxService, times(1)).publish(eq("StockItem"), eq(productId.toString()), eq("LowStockAlert"), any());

        // Replenished above threshold invalidates dedup state, then dropping again re-emits
        monitor.evaluateAfterStockDecrease(stock, storeId);
        monitor.evaluateAfterStockDecrease(stock, storeId);
        verify(outboxService, times(2)).publish(eq("StockItem"), eq(productId.toString()), eq("OutOfStock"), any());
        verify(outboxService, times(2)).publish(eq("StockItem"), eq(productId.toString()), eq("LowStockAlert"), any());
    }
}
