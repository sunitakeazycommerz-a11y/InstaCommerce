package com.instacommerce.inventory.service;

import com.instacommerce.inventory.config.InventoryProperties;
import com.instacommerce.inventory.domain.model.SkuSafetyStockBuffer;
import com.instacommerce.inventory.domain.model.StockItem;
import com.instacommerce.inventory.repository.SkuSafetyStockBufferRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class StockLevelMonitor {
    private final InventoryProperties inventoryProperties;
    private final OutboxService outboxService;
    private final SkuSafetyStockBufferRepository bufferRepository;
    private final SafetyStockService safetyStockService;

    public StockLevelMonitor(InventoryProperties inventoryProperties, OutboxService outboxService) {
        this(inventoryProperties, outboxService, (SkuSafetyStockBufferRepository) null, null);
    }

    @Autowired
    public StockLevelMonitor(InventoryProperties inventoryProperties,
                             OutboxService outboxService,
                             ObjectProvider<SkuSafetyStockBufferRepository> bufferRepositoryProvider,
                             ObjectProvider<SafetyStockService> safetyStockServiceProvider) {
        this(
            inventoryProperties,
            outboxService,
            bufferRepositoryProvider != null ? bufferRepositoryProvider.getIfAvailable() : null,
            safetyStockServiceProvider != null ? safetyStockServiceProvider.getIfAvailable() : null
        );
    }

    public StockLevelMonitor(InventoryProperties inventoryProperties,
                             OutboxService outboxService,
                             SkuSafetyStockBufferRepository bufferRepository,
                             SafetyStockService safetyStockService) {
        this.inventoryProperties = inventoryProperties;
        this.outboxService = outboxService;
        this.bufferRepository = bufferRepository;
        this.safetyStockService = safetyStockService;
    }

    public void evaluateAfterStockDecrease(StockItem stock, UUID storeId) {
        int available = Math.max(0, stock.getOnHand() - stock.getReserved());
        Optional<SkuSafetyStockBuffer> bufferOpt = bufferRepository != null
            ? bufferRepository.findByStoreIdAndProductId(storeId, stock.getProductId())
            : Optional.empty();
        int threshold = bufferOpt
            .map(SkuSafetyStockBuffer::getReorderThreshold)
            .orElse(inventoryProperties.getLowStockThreshold());
        Instant now = Instant.now();

        if (available == 0) {
            Map<String, Object> oosPayload = new LinkedHashMap<>();
            oosPayload.put("productId", stock.getProductId().toString());
            oosPayload.put("storeId", storeId.toString());
            oosPayload.put("detectedAt", now.toString());
            outboxService.publish("StockItem", stock.getProductId().toString(),
                "OutOfStock", oosPayload);
        }

        if (available <= threshold) {
            Map<String, Object> alertPayload = new LinkedHashMap<>();
            alertPayload.put("productId", stock.getProductId().toString());
            alertPayload.put("storeId", storeId.toString());
            alertPayload.put("warehouseId", storeId.toString());
            alertPayload.put("currentQuantity", available);
            alertPayload.put("threshold", threshold);
            alertPayload.put("detectedAt", now.toString());
            outboxService.publish("StockItem", stock.getProductId().toString(),
                "LowStockAlert", alertPayload);
        }

        if (bufferOpt.isPresent() && safetyStockService != null && available <= bufferOpt.get().getReorderThreshold()) {
            safetyStockService.evaluateBuffer(storeId, bufferOpt.get(), stock);
        }
    }
}
