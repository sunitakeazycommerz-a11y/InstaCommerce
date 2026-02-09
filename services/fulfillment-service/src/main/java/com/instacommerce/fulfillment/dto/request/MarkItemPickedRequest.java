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
    String note
) {
}
