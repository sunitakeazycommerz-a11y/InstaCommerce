package com.instacommerce.fulfillment.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.instacommerce.fulfillment.domain.model.PickItemStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MarkItemPickedRequest(
    @NotNull PickItemStatus status,
    @PositiveOrZero Integer pickedQty,
    UUID substituteProductId,
    String note,
    String scannedBarcode,
    String scannerHardwareId,
    Integer measuredWeightGrams,
    Long scanTimestampMs
) {
    public MarkItemPickedRequest(PickItemStatus status, Integer pickedQty, UUID substituteProductId, String note) {
        this(status, pickedQty, substituteProductId, note, null, null, null, null);
    }
}
