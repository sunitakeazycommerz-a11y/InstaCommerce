package com.instacommerce.order.client;

import com.instacommerce.order.dto.request.CartItem;
import com.instacommerce.order.workflow.model.ReserveResult;
import java.util.List;

public interface InventoryClient {
    ReserveResult reserveInventory(String idempotencyKey, String storeId, List<CartItem> items);

    void confirmReservation(String reservationId);

    void cancelReservation(String reservationId);
}
