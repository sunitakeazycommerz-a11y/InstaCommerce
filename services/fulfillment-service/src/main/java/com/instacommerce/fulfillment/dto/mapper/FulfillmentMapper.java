package com.instacommerce.fulfillment.dto.mapper;

import com.instacommerce.fulfillment.domain.model.Delivery;
import com.instacommerce.fulfillment.domain.model.PickItem;
import com.instacommerce.fulfillment.domain.model.PickTask;
import com.instacommerce.fulfillment.domain.model.Rider;
import com.instacommerce.fulfillment.dto.response.DeliveryResponse;
import com.instacommerce.fulfillment.dto.response.PickItemResponse;
import com.instacommerce.fulfillment.dto.response.PickTaskResponse;
import com.instacommerce.fulfillment.dto.response.RiderResponse;

public final class FulfillmentMapper {
    private FulfillmentMapper() {
    }

    public static PickTaskResponse toPickTaskResponse(PickTask task) {
        return new PickTaskResponse(
            task.getId(),
            task.getOrderId(),
            task.getStoreId(),
            task.getStatus().name(),
            task.getPickerId(),
            task.getStartedAt(),
            task.getCompletedAt(),
            task.getCreatedAt()
        );
    }

    public static PickItemResponse toPickItemResponse(PickItem item) {
        return new PickItemResponse(
            item.getId(),
            item.getProductId(),
            item.getProductName(),
            item.getSku(),
            item.getQuantity(),
            item.getPickedQty(),
            item.getUnitPriceCents(),
            item.getLineTotalCents(),
            item.getStatus().name(),
            item.getSubstituteProductId(),
            item.getNote(),
            item.getUpdatedAt()
        );
    }

    public static DeliveryResponse toDeliveryResponse(Delivery delivery) {
        return new DeliveryResponse(
            delivery.getId(),
            delivery.getOrderId(),
            delivery.getStatus().name(),
            delivery.getRider() == null ? null : delivery.getRider().getId(),
            delivery.getRider() == null ? null : delivery.getRider().getName(),
            delivery.getEstimatedMinutes(),
            delivery.getDispatchedAt(),
            delivery.getDeliveredAt()
        );
    }

    public static RiderResponse toRiderResponse(Rider rider) {
        return new RiderResponse(
            rider.getId(),
            rider.getName(),
            rider.getPhone(),
            rider.getStoreId(),
            rider.isAvailable(),
            rider.getCreatedAt()
        );
    }
}
