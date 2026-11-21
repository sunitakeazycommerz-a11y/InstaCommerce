package com.instacommerce.payment.service;

import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.exception.InvalidCurrencyException;
import com.instacommerce.payment.exception.PaymentGatewayException;
import java.util.Currency;
import java.util.Locale;

public final class PaymentSupport {
    private static final String DEFAULT_CURRENCY = "INR";

    private PaymentSupport() {
    }

    public static String normalizeCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return DEFAULT_CURRENCY;
        }
        String normalized = currency.trim().toUpperCase(Locale.ROOT);
        try {
            Currency.getInstance(normalized);
        } catch (IllegalArgumentException ex) {
            throw new InvalidCurrencyException(normalized);
        }
        return normalized;
    }

    public static String requireValidCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Currency is mandatory and must not be null or blank");
        }
        String normalized = currency.trim().toUpperCase(Locale.ROOT);
        try {
            Currency.getInstance(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported currency: " + currency, ex);
        }
        return normalized;
    }

    public static void ensurePspReference(Payment payment) {
        if (payment.getPspReference() == null || payment.getPspReference().isBlank()) {
            throw new PaymentGatewayException("PSP reference is missing for payment " + payment.getId());
        }
    }

    public static String normalizeReason(String reason, String defaultReason) {
        return (reason == null || reason.isBlank()) ? defaultReason : reason;
    }
}
