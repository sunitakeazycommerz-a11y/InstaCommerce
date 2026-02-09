package com.instacommerce.wallet.dto.response;

public record WalletResponse(
    long balanceCents,
    String currency
) {
}
