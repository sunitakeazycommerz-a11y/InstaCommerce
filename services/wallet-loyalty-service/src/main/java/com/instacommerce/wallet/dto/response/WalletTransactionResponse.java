package com.instacommerce.wallet.dto.response;

import java.time.Instant;

public record WalletTransactionResponse(
    String type,
    long amountCents,
    long balanceAfterCents,
    String referenceType,
    String referenceId,
    String description,
    Instant createdAt
) {
}
