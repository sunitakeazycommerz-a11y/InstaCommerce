package com.instacommerce.checkout.workflow.activity;

import com.instacommerce.checkout.dto.InventoryReservationResult;
import com.instacommerce.checkout.dto.InventoryReserveRequest;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface InventoryActivity {

    @ActivityMethod
    InventoryReservationResult reserveStock(InventoryReserveRequest request);

    @ActivityMethod
    void releaseStock(String reservationId);

    @ActivityMethod
    void confirmStock(String reservationId);
}
