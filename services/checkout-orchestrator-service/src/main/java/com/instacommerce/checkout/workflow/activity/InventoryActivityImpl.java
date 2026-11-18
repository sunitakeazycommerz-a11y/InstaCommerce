package com.instacommerce.checkout.workflow.activity;

import com.instacommerce.checkout.dto.InventoryReservationResult;
import com.instacommerce.checkout.dto.InventoryReserveRequest;
import com.instacommerce.checkout.exception.ServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.temporal.activity.Activity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class InventoryActivityImpl implements InventoryActivity {
    private static final Logger log = LoggerFactory.getLogger(InventoryActivityImpl.class);
    private final RestTemplate restTemplate;

    public InventoryActivityImpl(@Qualifier("inventoryRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    @CircuitBreaker(name = "inventoryService", fallbackMethod = "reserveStockFallback")
    public InventoryReservationResult reserveStock(InventoryReserveRequest request) {
        String resolvedKey = resolveIdempotencyKey(request.idempotencyKey());
        InventoryReserveRequest payload = new InventoryReserveRequest(resolvedKey, request.storeId(), request.items());
        int itemCount = request.items() != null ? request.items().size() : 0;
        log.info("Reserving stock for {} items storeId={} idempotencyKey={}", itemCount, request.storeId(), resolvedKey);
        return restTemplate.postForObject("/api/inventory/reservations", payload, InventoryReservationResult.class);
    }

    @Override
    @CircuitBreaker(name = "inventoryService", fallbackMethod = "releaseStockFallback")
    public void releaseStock(String reservationId) {
        log.info("Releasing stock reservation={}", reservationId);
        restTemplate.delete("/api/inventory/reservations/{reservationId}", reservationId);
    }

    @Override
    @CircuitBreaker(name = "inventoryService", fallbackMethod = "confirmStockFallback")
    public void confirmStock(String reservationId) {
        log.info("Confirming stock reservation={}", reservationId);
        restTemplate.postForObject("/api/inventory/reservations/{reservationId}/confirm",
            null, Void.class, reservationId);
    }

    @SuppressWarnings("unused")
    private InventoryReservationResult reserveStockFallback(InventoryReserveRequest request, Exception e) {
        int itemCount = request != null && request.items() != null ? request.items().size() : 0;
        log.warn("Circuit breaker fallback for inventoryService reserveStock items={}: {}", itemCount, e.getMessage());
        throw new ServiceUnavailableException("inventoryService",
                "Inventory service unavailable for reserveStock", e);
    }

    private String resolveIdempotencyKey(String providedKey) {
        if (providedKey != null && !providedKey.isBlank()) {
            return providedKey;
        }
        return Activity.getExecutionContext().getInfo().getActivityId();
    }

    @SuppressWarnings("unused")
    private void releaseStockFallback(String reservationId, Exception e) {
        log.warn("Circuit breaker fallback for inventoryService releaseStock reservation={}: {}",
                reservationId, e.getMessage());
        throw new ServiceUnavailableException("inventoryService",
                "Inventory service unavailable for releaseStock reservation=" + reservationId, e);
    }

    @SuppressWarnings("unused")
    private void confirmStockFallback(String reservationId, Exception e) {
        log.warn("Circuit breaker fallback for inventoryService confirmStock reservation={}: {}",
                reservationId, e.getMessage());
        throw new ServiceUnavailableException("inventoryService",
                "Inventory service unavailable for confirmStock reservation=" + reservationId, e);
    }
}
