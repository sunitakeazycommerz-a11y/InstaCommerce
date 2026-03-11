package com.instacommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.instacommerce.payment.exception.InvalidCurrencyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.instacommerce.payment.repository.LedgerEntryRepository;
import com.instacommerce.payment.repository.PaymentRepository;

@ExtendWith(MockitoExtension.class)
class PaymentTransactionHelperCurrencyTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private LedgerService ledgerService;
    @Mock private LedgerEntryRepository ledgerEntryRepository;
    @Mock private OutboxService outboxService;
    @Mock private AuditLogService auditLogService;

    @InjectMocks
    private PaymentTransactionHelper helper;

    @Nested
    @DisplayName("normalizeCurrency()")
    class NormalizeCurrency {

        @Test
        @DisplayName("null input defaults to INR")
        void nullDefaultsToInr() {
            assertThat(helper.normalizeCurrency(null)).isEqualTo("INR");
        }

        @Test
        @DisplayName("blank input defaults to INR")
        void blankDefaultsToInr() {
            assertThat(helper.normalizeCurrency("   ")).isEqualTo("INR");
        }

        @Test
        @DisplayName("empty string defaults to INR")
        void emptyDefaultsToInr() {
            assertThat(helper.normalizeCurrency("")).isEqualTo("INR");
        }

        @Test
        @DisplayName("valid lowercase input is normalized to uppercase")
        void lowercaseNormalized() {
            assertThat(helper.normalizeCurrency("usd")).isEqualTo("USD");
        }

        @Test
        @DisplayName("valid mixed-case input with whitespace is trimmed and uppercased")
        void mixedCaseTrimmed() {
            assertThat(helper.normalizeCurrency("  eur ")).isEqualTo("EUR");
        }

        @Test
        @DisplayName("valid INR input passes through")
        void inrPassesThrough() {
            assertThat(helper.normalizeCurrency("INR")).isEqualTo("INR");
        }

        @Test
        @DisplayName("invalid currency code throws InvalidCurrencyException")
        void invalidCurrencyThrows() {
            assertThatThrownBy(() -> helper.normalizeCurrency("XYZ"))
                .isInstanceOf(InvalidCurrencyException.class)
                .hasMessageContaining("Unsupported currency: XYZ");
        }

        @Test
        @DisplayName("another invalid currency code throws with correct message")
        void anotherInvalidCurrencyThrows() {
            assertThatThrownBy(() -> helper.normalizeCurrency("abc"))
                .isInstanceOf(InvalidCurrencyException.class)
                .hasMessageContaining("Unsupported currency: ABC");
        }
    }
}
