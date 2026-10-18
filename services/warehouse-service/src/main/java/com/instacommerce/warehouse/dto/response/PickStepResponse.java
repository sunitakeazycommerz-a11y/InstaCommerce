package com.instacommerce.warehouse.dto.response;

public record PickStepResponse(
    int sequenceNumber,
    String sku,
    String itemName,
    String aisle,
    String shelf,
    String bin,
    String category,
    int quantity,
    double distanceFromPreviousMeters,
    String navigationInstruction
) {
}
