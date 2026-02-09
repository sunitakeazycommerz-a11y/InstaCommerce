package com.instacommerce.checkout.workflow.activity;

import com.instacommerce.checkout.dto.CartItem;
import com.instacommerce.checkout.dto.InventoryReservationResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.List;

@ActivityInterface
public interface InventoryActivity {

    @ActivityMethod
    InventoryReservationResult reserveStock(List<CartItem> items);

    @ActivityMethod
    void releaseStock(String reservationId);

    @ActivityMethod
    void confirmStock(String reservationId);
}
