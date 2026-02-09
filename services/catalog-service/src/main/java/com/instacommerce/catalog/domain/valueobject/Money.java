package com.instacommerce.catalog.domain.valueobject;

public record Money(long amountCents, String currency) {
    public Money {
        if (amountCents < 0) {
            throw new IllegalArgumentException("amountCents must be non-negative");
        }
    }

    public static Money of(long amountCents, String currency) {
        return new Money(amountCents, currency);
    }
}
