package com.instacommerce.checkout.workflow.activity;

import com.instacommerce.checkout.dto.CartItem;
import com.instacommerce.checkout.dto.InventoryReservationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Component
public class InventoryActivityImpl implements InventoryActivity {
    private static final Logger log = LoggerFactory.getLogger(InventoryActivityImpl.class);
    private final RestTemplate restTemplate;

    public InventoryActivityImpl(@Qualifier("inventoryRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public InventoryReservationResult reserveStock(List<CartItem> items) {
        log.info("Reserving stock for {} items", items.size());
        return restTemplate.postForObject("/api/inventory/reservations",
            Map.of("items", items), InventoryReservationResult.class);
    }

    @Override
    public void releaseStock(String reservationId) {
        log.info("Releasing stock reservation={}", reservationId);
        restTemplate.delete("/api/inventory/reservations/{reservationId}", reservationId);
    }

    @Override
    public void confirmStock(String reservationId) {
        log.info("Confirming stock reservation={}", reservationId);
        restTemplate.postForObject("/api/inventory/reservations/{reservationId}/confirm",
            null, Void.class, reservationId);
    }
}
