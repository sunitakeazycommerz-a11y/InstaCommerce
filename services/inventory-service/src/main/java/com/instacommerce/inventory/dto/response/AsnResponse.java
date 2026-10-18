package com.instacommerce.inventory.dto.response;

import com.instacommerce.inventory.domain.model.AsnStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AsnResponse(
    UUID id,
    String asnNumber,
    String purchaseOrderId,
    String vendorId,
    UUID storeId,
    String palletBarcode,
    AsnStatus status,
    Instant receivedAt,
    String receivedBy,
    Instant createdAt,
    List<AsnLineItemResponse> items
) {
    public record AsnLineItemResponse(
        UUID id,
        UUID productId,
        int expectedQuantity,
        int receivedQuantity,
        String batchNumber,
        Instant expiryDate
    ) {}
}
