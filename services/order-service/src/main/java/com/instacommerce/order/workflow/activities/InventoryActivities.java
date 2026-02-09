package com.instacommerce.order.workflow.activities;

import com.instacommerce.order.dto.request.CartItem;
import com.instacommerce.order.workflow.model.ReserveResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.List;

@ActivityInterface
public interface InventoryActivities {
    @ActivityMethod
    ReserveResult reserveInventory(String idempotencyKey, String storeId, List<CartItem> items);

    @ActivityMethod
    void confirmReservation(String reservationId);

    @ActivityMethod
    void cancelReservation(String reservationId);
}
