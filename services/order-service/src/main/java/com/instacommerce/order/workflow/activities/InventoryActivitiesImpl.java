package com.instacommerce.order.workflow.activities;

import com.instacommerce.order.client.InventoryClient;
import com.instacommerce.order.dto.request.CartItem;
import com.instacommerce.order.workflow.model.ReserveResult;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class InventoryActivitiesImpl implements InventoryActivities {
    private final InventoryClient inventoryClient;

    public InventoryActivitiesImpl(InventoryClient inventoryClient) {
        this.inventoryClient = inventoryClient;
    }

    @Override
    public ReserveResult reserveInventory(String idempotencyKey, String storeId, List<CartItem> items) {
        return inventoryClient.reserveInventory(idempotencyKey, storeId, items);
    }

    @Override
    public void confirmReservation(String reservationId) {
        inventoryClient.confirmReservation(reservationId);
    }

    @Override
    public void cancelReservation(String reservationId) {
        inventoryClient.cancelReservation(reservationId);
    }
}
