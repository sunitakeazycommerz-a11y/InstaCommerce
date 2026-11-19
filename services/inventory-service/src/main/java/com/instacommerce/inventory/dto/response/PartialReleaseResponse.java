package com.instacommerce.inventory.dto.response;

import com.instacommerce.inventory.domain.model.ReservationStatus;
import java.util.List;
import java.util.UUID;

public record PartialReleaseResponse(
    UUID reservationId,
    ReservationStatus status,
    List<ReservedItemResponse> releasedItems,
    List<ReservedItemResponse> remainingItems
) {
}
