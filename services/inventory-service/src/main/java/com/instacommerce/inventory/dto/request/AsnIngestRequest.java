package com.instacommerce.inventory.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AsnIngestRequest(
    @NotBlank String asnNumber,
    @NotBlank String purchaseOrderId,
    @NotBlank String vendorId,
    @NotNull UUID storeId,
    @NotBlank String palletBarcode,
    @NotEmpty List<@Valid AsnItemRequest> items
) {
    public record AsnItemRequest(
        @NotNull UUID productId,
        @Min(1) int expectedQuantity,
        String batchNumber,
        Instant expiryDate
    ) {}
}
