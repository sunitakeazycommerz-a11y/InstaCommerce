package com.instacommerce.inventory.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.instacommerce.inventory.config.InventoryProperties;
import com.instacommerce.inventory.domain.model.SkuSafetyStockBuffer;
import com.instacommerce.inventory.domain.model.StockItem;
import com.instacommerce.inventory.repository.SkuSafetyStockBufferRepository;
import java.time.Clock;
import java.time.Duration;
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
    private final FefoBatchAllocationService fefoBatchAllocationService;
    private final Clock clock;
    private final Cache<String, Instant> alertCooldownCache = Caffeine.newBuilder()
        .maximumSize(50_000)
        .expireAfterWrite(Duration.ofHours(1))
        .build();

    public StockLevelMonitor(InventoryProperties inventoryProperties, OutboxService outboxService) {
        this(inventoryProperties, outboxService, (SkuSafetyStockBufferRepository) null, null, null, Clock.systemUTC());
    }

    public StockLevelMonitor(InventoryProperties inventoryProperties,
                             OutboxService outboxService,
                             SkuSafetyStockBufferRepository bufferRepository,
                             SafetyStockService safetyStockService) {
        this(inventoryProperties, outboxService, bufferRepository, safetyStockService, null, Clock.systemUTC());
    }

    public StockLevelMonitor(InventoryProperties inventoryProperties,
                             OutboxService outboxService,
                             SkuSafetyStockBufferRepository bufferRepository,
                             SafetyStockService safetyStockService,
                             FefoBatchAllocationService fefoBatchAllocationService,
                             Clock clock) {
        this.inventoryProperties = inventoryProperties;
        this.outboxService = outboxService;
        this.bufferRepository = bufferRepository;
        this.safetyStockService = safetyStockService;
        this.fefoBatchAllocationService = fefoBatchAllocationService;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Autowired
    public StockLevelMonitor(InventoryProperties inventoryProperties,
                             OutboxService outboxService,
                             ObjectProvider<SkuSafetyStockBufferRepository> bufferRepositoryProvider,
                             ObjectProvider<SafetyStockService> safetyStockServiceProvider,
                             ObjectProvider<FefoBatchAllocationService> fefoBatchAllocationServiceProvider,
                             ObjectProvider<Clock> clockProvider) {
        this(
            inventoryProperties,
            outboxService,
            bufferRepositoryProvider != null ? bufferRepositoryProvider.getIfAvailable() : null,
            safetyStockServiceProvider != null ? safetyStockServiceProvider.getIfAvailable() : null,
            fefoBatchAllocationServiceProvider != null ? fefoBatchAllocationServiceProvider.getIfAvailable() : null,
            clockProvider != null ? clockProvider.getIfAvailable(Clock::systemUTC) : Clock.systemUTC()
        );
    }

    public void evaluateAfterStockDecrease(StockItem stock, UUID storeId) {
        int available = fefoBatchAllocationService != null
            ? fefoBatchAllocationService.resolveSellableAvailable(stock)
            : Math.max(0, stock.getOnHand() - stock.getReserved());
        Optional<SkuSafetyStockBuffer> bufferOpt = bufferRepository != null
            ? bufferRepository.findByStoreIdAndProductId(storeId, stock.getProductId())
            : Optional.empty();
        int threshold = bufferOpt
            .map(SkuSafetyStockBuffer::getReorderThreshold)
            .orElse(inventoryProperties.getLowStockThreshold());
        Instant now = Instant.now(clock);
        Duration cooldown = Duration.ofMinutes(Math.max(0, inventoryProperties.getAlertCooldownMinutes()));

        String oosKey = "OOS:" + storeId + ":" + stock.getProductId();
        String lowKey = "LOW:" + storeId + ":" + stock.getProductId();

        if (available == 0) {
            if (shouldEmitAlert(oosKey, now, cooldown)) {
                Map<String, Object> oosPayload = new LinkedHashMap<>();
                oosPayload.put("productId", stock.getProductId().toString());
                oosPayload.put("storeId", storeId.toString());
                oosPayload.put("detectedAt", now.toString());
                outboxService.publish("StockItem", stock.getProductId().toString(),
                    "OutOfStock", oosPayload);
            }
        } else {
            alertCooldownCache.invalidate(oosKey);
        }

        if (available <= threshold) {
            if (shouldEmitAlert(lowKey, now, cooldown)) {
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
        } else {
            alertCooldownCache.invalidate(lowKey);
        }

        if (bufferOpt.isPresent() && safetyStockService != null && available <= bufferOpt.get().getReorderThreshold()) {
            safetyStockService.evaluateBuffer(storeId, bufferOpt.get(), stock);
        }
    }

    private boolean shouldEmitAlert(String key, Instant now, Duration cooldown) {
        if (cooldown.isZero()) {
            return true;
        }
        Instant previous = alertCooldownCache.getIfPresent(key);
        if (previous != null && Duration.between(previous, now).compareTo(cooldown) < 0) {
            return false;
        }
        alertCooldownCache.put(key, now);
        return true;
    }
}
