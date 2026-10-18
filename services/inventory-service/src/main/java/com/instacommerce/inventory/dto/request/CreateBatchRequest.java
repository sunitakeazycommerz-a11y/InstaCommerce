package com.instacommerce.inventory.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public record CreateBatchRequest(
    @NotNull UUID productId,
    @NotBlank String batchNumber,
    @Min(1) int quantity,
    @NotNull Instant expiryDate
) {}
