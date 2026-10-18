package com.instacommerce.inventory.service;

import com.instacommerce.inventory.domain.model.SkuSafetyStockBuffer;
import com.instacommerce.inventory.domain.model.StockItem;
import com.instacommerce.inventory.dto.request.SafetyStockItemRequest;
import com.instacommerce.inventory.dto.response.SafetyStockResponse;
import com.instacommerce.inventory.repository.SkuSafetyStockBufferRepository;
import com.instacommerce.inventory.repository.StockItemRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SafetyStockService {
    private static final Logger log = LoggerFactory.getLogger(SafetyStockService.class);

    private final SkuSafetyStockBufferRepository bufferRepository;
    private final StockItemRepository stockItemRepository;
    private final OutboxService outboxService;

    public SafetyStockService(SkuSafetyStockBufferRepository bufferRepository,
                              StockItemRepository stockItemRepository,
                              OutboxService outboxService) {
        this.bufferRepository = bufferRepository;
        this.stockItemRepository = stockItemRepository;
        this.outboxService = outboxService;
    }

    @Transactional
    public SafetyStockResponse ingestBuffer(UUID storeId, SafetyStockItemRequest request) {
        SkuSafetyStockBuffer buffer = bufferRepository.findByStoreIdAndProductId(storeId, request.productId())
            .orElseGet(() -> {
                SkuSafetyStockBuffer newBuffer = new SkuSafetyStockBuffer();
                newBuffer.setStoreId(storeId);
                newBuffer.setProductId(request.productId());
                return newBuffer;
            });

        buffer.setZScore(request.zScore());
        buffer.setStdDevDemand(request.stdDevDemand());
        buffer.setLeadTimeDays(request.leadTimeDays());
        buffer.recalculate();
        buffer = bufferRepository.save(buffer);

        // Evaluate against current stock
        return evaluateAndReorderIfNeeded(storeId, buffer);
    }

    @Transactional
    public List<SafetyStockResponse> ingestBatch(UUID storeId, List<SafetyStockItemRequest> requests) {
        List<SafetyStockResponse> responses = new ArrayList<>();
        for (SafetyStockItemRequest req : requests) {
            responses.add(ingestBuffer(storeId, req));
        }
        return responses;
    }

    @Transactional(readOnly = true)
    public Optional<SafetyStockResponse> getBuffer(UUID storeId, UUID productId) {
        return bufferRepository.findByStoreIdAndProductId(storeId, productId)
            .map(buffer -> {
                StockItem stock = stockItemRepository.findByStoreIdAndProductId(storeId, productId).orElse(null);
                int onHand = stock != null ? stock.getOnHand() : 0;
                int reserved = stock != null ? stock.getReserved() : 0;
                int available = Math.max(0, onHand - reserved);
                boolean reorderNeeded = available <= buffer.getReorderThreshold();
                return toResponse(buffer, onHand, available, reorderNeeded);
            });
    }

    @Transactional(readOnly = true)
    public List<SafetyStockResponse> getStoreBuffers(UUID storeId) {
        List<SkuSafetyStockBuffer> buffers = bufferRepository.findByStoreId(storeId);
        List<SafetyStockResponse> responses = new ArrayList<>();
        for (SkuSafetyStockBuffer buffer : buffers) {
            StockItem stock = stockItemRepository.findByStoreIdAndProductId(storeId, buffer.getProductId()).orElse(null);
            int onHand = stock != null ? stock.getOnHand() : 0;
            int reserved = stock != null ? stock.getReserved() : 0;
            int available = Math.max(0, onHand - reserved);
            boolean reorderNeeded = available <= buffer.getReorderThreshold();
            responses.add(toResponse(buffer, onHand, available, reorderNeeded));
        }
        return responses;
    }

    @Transactional
    public List<SafetyStockResponse> checkStoreReorders(UUID storeId) {
        List<SkuSafetyStockBuffer> buffers = bufferRepository.findByStoreId(storeId);
        List<SafetyStockResponse> responses = new ArrayList<>();
        for (SkuSafetyStockBuffer buffer : buffers) {
            responses.add(evaluateAndReorderIfNeeded(storeId, buffer));
        }
        return responses;
    }

    private SafetyStockResponse evaluateAndReorderIfNeeded(UUID storeId, SkuSafetyStockBuffer buffer) {
        StockItem stock = stockItemRepository.findByStoreIdAndProductId(storeId, buffer.getProductId()).orElse(null);
        int onHand = stock != null ? stock.getOnHand() : 0;
        int reserved = stock != null ? stock.getReserved() : 0;
        int available = Math.max(0, onHand - reserved);
        boolean reorderNeeded = available <= buffer.getReorderThreshold();

        if (reorderNeeded) {
            // Check throttle or trigger PO
            Instant now = Instant.now();
            buffer.setLastReorderEventAt(now);
            bufferRepository.save(buffer);

            int recommendedOrderQuantity = Math.max(buffer.getCalculatedSafetyStock() * 2,
                (buffer.getReorderThreshold() - available) + buffer.getCalculatedSafetyStock());

            Map<String, Object> payload = new HashMap<>();
            payload.put("store_id", storeId.toString());
            payload.put("product_id", buffer.getProductId().toString());
            payload.put("available_stock", available);
            payload.put("safety_stock_buffer", buffer.getCalculatedSafetyStock());
            payload.put("reorder_threshold", buffer.getReorderThreshold());
            payload.put("recommended_order_quantity", recommendedOrderQuantity);
            payload.put("lead_time_days", buffer.getLeadTimeDays());
            payload.put("timestamp", now.toString());

            outboxService.publish("INVENTORY_SAFETY_STOCK", storeId + ":" + buffer.getProductId(),
                "ReorderPointReached", payload);

            log.info("Triggered automated vendor purchase order alert: store={} sku={} available={} threshold={}",
                storeId, buffer.getProductId(), available, buffer.getReorderThreshold());
        }

        return toResponse(buffer, onHand, available, reorderNeeded);
    }

    private SafetyStockResponse toResponse(SkuSafetyStockBuffer buffer, int onHand, int available, boolean reorderNeeded) {
        return new SafetyStockResponse(
            buffer.getStoreId(),
            buffer.getProductId(),
            buffer.getZScore(),
            buffer.getStdDevDemand(),
            buffer.getLeadTimeDays(),
            buffer.getCalculatedSafetyStock(),
            buffer.getReorderThreshold(),
            onHand,
            available,
            reorderNeeded,
            buffer.getLastReorderEventAt()
        );
    }
}
