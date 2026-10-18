package com.instacommerce.inventory.dto.request;

import jakarta.validation.constraints.NotBlank;

public record PalletReceiptRequest(
    @NotBlank String receivedBy
) {}
